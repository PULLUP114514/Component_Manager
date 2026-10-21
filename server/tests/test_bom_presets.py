from fastapi.testclient import TestClient
import pytest

from app.config import get_settings
from app.main import create_app


ADMIN = {"Authorization": "Bearer preset-test"}


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("API_TOKEN", "preset-test")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "inventory.db"))
    monkeypatch.setenv("MQTT_ENABLED", "false")
    get_settings.cache_clear()
    with TestClient(create_app()) as value:
        yield value
    get_settings.cache_clear()


def _preset(**changes):
    return {
        "id": "preset-1", "name": "Board", "updated_at": "2026-10-22T12:00:00Z",
        "deleted": False, "content": {
            "schema_version": 1, "project_name": "Board", "production_sets": 2,
            "source_name": "board.csv", "file_sha256": "", "available_sheets": [],
            "selected_sheet": {"name": "BOM", "index": 0, "hidden": False}, "warnings": [],
            "lines": [{"id": "row-1", "sku": None, "name": "Resistor", "model": None,
                       "package_name": "0603", "quantity_per_set": 3, "selection": "",
                       "excluded": True, "source_rows": [{"sheet_name": "BOM", "row_number": 2,
                       "quantity_per_set": 3, "fields": {"reference": "R1,R2,R3"}}]}],
        },
    } | changes


def _sync(client, presets, headers=ADMIN):
    return client.post("/sync/bom-presets", headers=headers,
                       json={"device_id": "test", "presets": presets})


def test_round_trip_lww_tombstones_retry_and_inventory_unchanged(client):
    assert client.get("/health").json()["bom_preset_protocol"] == 1
    assert client.post("/auth/ping", headers=ADMIN).json()["bom_preset_protocol"] == 1
    assert _sync(client, []).json()["presets"] == []
    first = _sync(client, [_preset()])
    assert first.status_code == 200, first.text
    original = first.json()["presets"]
    assert original[0]["content"] == _preset()["content"]
    assert _sync(client, [_preset()]).json()["presets"] == original
    assert _sync(client, [_preset(name="Equal timestamp loses")]).json()["presets"] == original
    assert _sync(client, [_preset(name="Stale", updated_at="2026-10-22T11:00:00Z")]).json()["presets"] == original
    tombstone = _preset(deleted=True, content=None, updated_at="2026-10-22T13:00:00+01:00")
    assert _sync(client, [tombstone]).json()["presets"] == original  # same instant
    tombstone["updated_at"] = "2026-10-22T14:00:00+01:00"
    deleted = _sync(client, [tombstone]).json()["presets"]
    assert deleted[0]["deleted"] is True and deleted[0]["content"] is None
    assert _sync(client, [_preset()]).json()["presets"] == deleted
    assert client.get("/admin-api/bom-presets", headers=ADMIN).json()["presets"] == deleted
    stock = client.get("/sync/pull", headers=ADMIN).json()
    assert stock["components"] == stock["stock_movements"] == []
    assert stock["sync_cursor"] == 0


def test_account_isolation_auth_binding_and_cookie_read(client):
    assert _sync(client, [], {}).status_code == 401
    accounts = [client.post("/admin-api/accounts", headers=ADMIN, json={"name": name}).json()
                for name in ("Alice", "Bob")]
    alice, bob = accounts
    auth = {"Authorization": f"Bearer {alice['api_token']}"}
    assert _sync(client, [], auth).status_code == 409
    assert _sync(client, [], auth | {"X-Component-Vault-Account-Id": bob["account_id"]}).status_code == 403
    auth["X-Component-Vault-Account-Id"] = alice["account_id"]
    assert _sync(client, [_preset()], auth).status_code == 200
    assert client.get("/admin-api/bom-presets", headers={"Authorization": f"Bearer {bob['api_token']}"}).json()["presets"] == []
    assert _sync(client, []).json()["presets"] == []
    assert client.post("/auth/session", json={"api_key": alice["api_token"]}).status_code == 200
    assert client.get("/admin-api/bom-presets").json()["presets"][0]["id"] == "preset-1"


@pytest.mark.parametrize("change", [
    {"content": None},
    {"content": _preset()["content"] | {"production_sets": 0}},
    {"content": _preset()["content"] | {"production_sets": 2147483647}},
    {"content": _preset()["content"] | {"lines": _preset()["content"]["lines"] * 2}},
])
def test_invalid_payload_does_not_write(client, change):
    assert _sync(client, [_preset(**change)]).status_code == 422
    assert _sync(client, []).json()["presets"] == []


def test_shared_portable_fixture_round_trip(client):
    import json
    from pathlib import Path

    fixture = Path(__file__).resolve().parents[2] / "test-data" / "bom-preset-v1.json"
    records = json.loads(fixture.read_text(encoding="utf-8-sig"))["presets"]
    response = _sync(client, records)
    assert response.status_code == 200, response.text
    for actual, expected in zip(response.json()["presets"], records, strict=True):
        assert actual == expected
