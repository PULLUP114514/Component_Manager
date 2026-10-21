from __future__ import annotations

from datetime import datetime, timezone
import json
import sqlite3
from typing import Literal

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field, model_validator

from .auth import require_sync_account, require_token
from .database import get_db
from .repositories import _to_storage_time


class BomPresetSheet(BaseModel):
    name: str
    index: int = Field(strict=True)
    hidden: bool


class BomPresetSourceRow(BaseModel):
    sheet_name: str
    row_number: int = Field(strict=True)
    quantity_per_set: int = Field(strict=True)
    fields: dict[str, str]


class BomPresetLine(BaseModel):
    id: str = Field(min_length=1)
    sku: str | None
    name: str | None
    model: str | None
    package_name: str | None
    quantity_per_set: int = Field(gt=0, le=2147483647, strict=True)
    selection: str | None
    excluded: bool
    source_rows: list[BomPresetSourceRow]


class BomPresetContent(BaseModel):
    schema_version: Literal[1]
    project_name: str
    production_sets: int = Field(gt=0, le=2147483647, strict=True)
    source_name: str
    file_sha256: str
    available_sheets: list[BomPresetSheet]
    selected_sheet: BomPresetSheet
    warnings: list[str]
    lines: list[BomPresetLine] = Field(max_length=5000)

    @model_validator(mode="after")
    def validate_lines(self) -> BomPresetContent:
        ids = [line.id for line in self.lines]
        if len(ids) != len(set(ids)):
            raise ValueError("BOM preset line IDs must be unique.")
        if any(line.quantity_per_set * self.production_sets > 2147483647 for line in self.lines):
            raise ValueError("BOM production quantity exceeds the supported range.")
        return self


class BomPresetRecord(BaseModel):
    id: str = Field(min_length=1)
    name: str = Field(min_length=1)
    updated_at: datetime
    deleted: bool
    content: BomPresetContent | None

    @model_validator(mode="after")
    def validate_content(self) -> BomPresetRecord:
        if not self.deleted and self.content is None:
            raise ValueError("Active BOM presets require content.")
        return self


class BomPresetSyncRequest(BaseModel):
    device_id: str = Field(min_length=1)
    presets: list[BomPresetRecord] = Field(default_factory=list)


class BomPresetSyncResponse(BaseModel):
    server_time: datetime
    bom_preset_protocol: Literal[1] = 1
    presets: list[BomPresetRecord]


router = APIRouter(tags=["bom-presets"])


def _snapshot(connection: sqlite3.Connection) -> BomPresetSyncResponse:
    return BomPresetSyncResponse(
        server_time=datetime.now(timezone.utc),
        presets=[BomPresetRecord(
            id=row["id"], name=row["name"], updated_at=row["updated_at"],
            deleted=bool(row["deleted"]),
            content=json.loads(row["content_json"]) if row["content_json"] is not None else None,
        ) for row in connection.execute("SELECT * FROM bom_presets ORDER BY id")],
    )


@router.post("/sync/bom-presets", response_model=BomPresetSyncResponse,
             dependencies=[Depends(require_sync_account)])
def sync_bom_presets(
    payload: BomPresetSyncRequest,
    connection: sqlite3.Connection = Depends(get_db),
) -> BomPresetSyncResponse:
    # A complete snapshot and its writes share one short transaction; presets do
    # not touch inventory revisions, quantities, or stock movements.
    with connection:
        connection.execute("BEGIN IMMEDIATE")
        for preset in payload.presets:
            connection.execute(
                """INSERT INTO bom_presets (id, name, updated_at, deleted, content_json)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name,
                    updated_at=excluded.updated_at, deleted=excluded.deleted,
                    content_json=excluded.content_json
                WHERE excluded.updated_at > bom_presets.updated_at""",
                (preset.id, preset.name, _to_storage_time(preset.updated_at), int(preset.deleted),
                 json.dumps(preset.content.model_dump(), ensure_ascii=False) if preset.content is not None else None),
            )
        result = _snapshot(connection)
    return result


@router.get("/admin-api/bom-presets", response_model=BomPresetSyncResponse,
            dependencies=[Depends(require_token)])
def list_bom_presets(connection: sqlite3.Connection = Depends(get_db)) -> BomPresetSyncResponse:
    return _snapshot(connection)
