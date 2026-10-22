import hashlib
import os
import sqlite3
import time

from fastapi.testclient import TestClient
import pytest

from app.config import get_settings
from app.main import create_app
from app.photos import MAX_PHOTO_BYTES, photo_directory

PNG = b"\x89PNG\r\n\x1a\n" + b"photo-original"
KEY = hashlib.sha256(PNG).hexdigest() + ".png"
ADMIN = {"Authorization": "Bearer photo-test", "X-Component-Vault-Account-Id": "admin"}


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("API_TOKEN", "photo-test")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "inventory.db"))
    monkeypatch.setenv("MQTT_ENABLED", "false")
    get_settings.cache_clear()
    with TestClient(create_app()) as value:
        yield value
    get_settings.cache_clear()


def reference(client, key=KEY, headers=ADMIN, **changes):
    component = {"id": "part", "sku": "part", "name": "part", "category": "part", "package_name": "part",
                 "location": "cabinet", "description": "本地图片：" + key, "quantity": 0, "min_stock": 0,
                 "updated_at": "2026-10-22T12:00:00Z", "deleted": False} | changes
    result = client.post("/sync/push", headers=headers, json={"device_id": "photo", "components": [component]})
    assert result.status_code == 200, result.text


def put(client, key=KEY, data=PNG, headers=ADMIN):
    return client.put("/sync/photos/" + key, headers=headers | {"Content-Type": "image/png"}, content=data)


def test_photo_roundtrip_metadata_removal_and_cleanup(client):
    assert client.get("/health").json()["photo_protocol"] == 1
    assert client.post("/auth/ping", headers=ADMIN).json()["photo_protocol"] == 1
    assert client.get("/sync/photos", headers=ADMIN).json()["photos"] == []
    reference(client)
    assert client.get("/sync/photos", headers=ADMIN).json()["photos"] == [{"key": KEY, "available": False, "size_bytes": None}]
    assert client.get("/sync/photos/" + KEY, headers=ADMIN).status_code == 404
    for _ in range(2):
        assert put(client).json() == {"key": KEY, "size_bytes": len(PNG)}
    assert client.get("/sync/photos/" + KEY, headers=ADMIN).content == PNG
    listed = client.get("/admin-api/components", headers=ADMIN).json()["items"][0]
    assert listed["photo_key"] == KEY and listed["photo_available"] is True
    assert client.get("/admin-api/components/part", headers=ADMIN).json()["photo_available"] is True
    assert client.post("/auth/session", json={"api_key": "photo-test"}).status_code == 200
    assert client.get("/admin-api/component-photos/" + KEY).content == PNG
    reference(client, description="removed", updated_at="2026-10-22T13:00:00Z")
    assert client.get("/sync/photos/" + KEY, headers=ADMIN).status_code == 404
    directory = photo_directory(get_settings().database_path)
    path = directory / KEY
    assert path.exists()  # Grace keeps a recently unreferenced original.
    os.utime(path, (time.time() - 90000,) * 2)
    abandoned = directory / ".upload-abandoned"
    abandoned.write_bytes(b"partial")
    os.utime(abandoned, (time.time() - 90000,) * 2)
    assert client.get("/sync/photos", headers=ADMIN).json()["photos"] == []
    assert not path.exists() and not abandoned.exists()
    assert client.get("/sync/pull", headers=ADMIN).json()["components"][0]["quantity"] == 0


def test_validation_failure_preserves_complete_blob(client):
    reference(client)
    assert put(client).status_code == 200
    assert put(client, data=b"invalid").status_code == 422
    assert put(client, data=PNG + b"changed").status_code == 422
    assert put(client, data=b"x" * (MAX_PHOTO_BYTES + 1)).status_code == 413
    assert client.put("/sync/photos/" + KEY, headers=ADMIN | {"Content-Type": "image/jpeg"}, content=PNG).status_code == 415
    assert client.get("/sync/photos/" + KEY, headers=ADMIN).content == PNG
    assert not list(photo_directory(get_settings().database_path).glob(".upload-*"))


def test_account_binding_isolation_and_account_deletion(client):
    assert client.get("/sync/photos").status_code == 401
    alice, bob = [client.post("/admin-api/accounts", headers=ADMIN, json={"name": n}).json() for n in ("Alice", "Bob")]
    auth = {"Authorization": "Bearer " + alice["api_token"]}
    assert client.get("/sync/photos", headers=auth).status_code == 409
    assert client.get("/sync/photos", headers=auth | {"X-Component-Vault-Account-Id": bob["account_id"]}).status_code == 403
    auth["X-Component-Vault-Account-Id"] = alice["account_id"]
    reference(client, headers=auth)
    assert put(client, headers=auth).status_code == 200
    assert client.get("/sync/photos", headers=ADMIN).json()["photos"] == []
    assert client.get("/admin-api/component-photos/" + KEY, headers={"Authorization": "Bearer " + bob["api_token"]}).status_code == 404
    directory = photo_directory(str(__import__("pathlib").Path(get_settings().database_path).parent / "users" / (alice["account_id"] + ".db")))
    assert directory.exists()
    deleted = client.delete("/admin-api/accounts/" + alice["account_id"], headers=ADMIN)
    assert deleted.status_code == 200, deleted.text
    assert not directory.exists()
