from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import sqlite3
import tempfile
import time

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from starlette.concurrency import run_in_threadpool

from .accounts import Account
from .auth import require_sync_account, require_token
from .database import get_db

MAX_PHOTO_BYTES = 5 * 1024 * 1024
KEY = re.compile(r"[0-9a-f]{64}\.(?:png|jpg)")
MARKER = re.compile(r"本地图片：[ \t]*([0-9a-f]{64}\.(?:png|jpg))(?=$|[\s；;])")
router = APIRouter(tags=["photos"])


def photo_key(description: str | None) -> str | None:
    match = MARKER.search(description or "")
    return match.group(1) if match else None


def photo_directory(database_path: str) -> Path:
    return Path(database_path + ".photos")


def referenced_keys(connection: sqlite3.Connection) -> set[str]:
    return {key for row in connection.execute(
        "SELECT description FROM components WHERE deleted = 0 AND description IS NOT NULL"
    ) if (key := photo_key(row[0])) is not None}


def photo_fields(connection: sqlite3.Connection, description: str | None) -> dict:
    key = photo_key(description)
    database_path = connection.execute("PRAGMA database_list").fetchone()[2]
    return {"photo_key": key, "photo_available": bool(
        key and (photo_directory(database_path) / key).is_file())}


def _referenced_path(key: str, connection: sqlite3.Connection, account: Account) -> Path:
    if KEY.fullmatch(key) is None or key not in referenced_keys(connection):
        raise HTTPException(status_code=404, detail="Photo is not referenced by this account.")
    return photo_directory(account.database_path) / key


def prune_photos(directory: Path, referenced: set[str]) -> None:
    if not directory.is_dir():
        return
    cutoff = time.time() - 24 * 60 * 60
    for path in directory.iterdir():
        if path.name in referenced or not path.is_file():
            continue
        if not (KEY.fullmatch(path.name) or path.name.startswith(".upload-")):
            continue
        try:
            if path.stat().st_mtime < cutoff:
                path.unlink(missing_ok=True)
        except FileNotFoundError:
            pass  # Another request may finish or prune a temporary file.


@router.get("/sync/photos")
def manifest(connection: sqlite3.Connection = Depends(get_db),
             account: Account = Depends(require_sync_account)) -> dict:
    keys = referenced_keys(connection)
    directory = photo_directory(account.database_path)
    prune_photos(directory, keys)
    photos = []
    for key in sorted(keys):
        path = directory / key
        size = path.stat().st_size if path.is_file() else None
        photos.append({"key": key, "available": size is not None, "size_bytes": size})
    return {"photo_protocol": 1, "max_photo_bytes": MAX_PHOTO_BYTES, "photos": photos}


@router.put("/sync/photos/{key}")
async def upload(key: str, request: Request,
                 connection: sqlite3.Connection = Depends(get_db),
                 account: Account = Depends(require_sync_account)) -> dict:
    path = _referenced_path(key, connection, account)
    expected_type = "image/png" if key.endswith(".png") else "image/jpeg"
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != expected_type:
        raise HTTPException(status_code=415, detail="Photo content type must match its key.")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".upload-", dir=path.parent)
    size = 0
    digest = hashlib.sha256()
    prefix = b""
    try:
        with os.fdopen(descriptor, "wb") as output:
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_PHOTO_BYTES:
                    raise HTTPException(status_code=413, detail="Photo exceeds 5 MiB.")
                digest.update(chunk)
                prefix = (prefix + chunk)[:8]
                await run_in_threadpool(output.write, chunk)
        signature_ok = (prefix.startswith(b"\x89PNG\r\n\x1a\n") if key.endswith(".png")
                        else prefix.startswith(b"\xff\xd8\xff"))
        if not signature_ok or digest.hexdigest() != key.split(".")[0]:
            raise HTTPException(status_code=422, detail="Photo hash or image signature does not match its key.")
        # Recheck after streaming: replacement/removal uses component LWW metadata.
        _referenced_path(key, connection, account)
        await run_in_threadpool(os.replace, temporary, path)
        return {"key": key, "size_bytes": size}
    finally:
        Path(temporary).unlink(missing_ok=True)


def _download(key: str, connection: sqlite3.Connection, account: Account) -> FileResponse:
    path = _referenced_path(key, connection, account)
    if not path.is_file():
        raise HTTPException(status_code=404, detail="Photo original is missing; sync a device that has it.")
    return FileResponse(path, media_type="image/png" if key.endswith(".png") else "image/jpeg")


@router.get("/sync/photos/{key}")
def download(key: str, connection: sqlite3.Connection = Depends(get_db),
             account: Account = Depends(require_sync_account)) -> FileResponse:
    return _download(key, connection, account)


@router.get("/admin-api/component-photos/{key}")
def web_download(key: str, connection: sqlite3.Connection = Depends(get_db),
                 account: Account = Depends(require_token)) -> FileResponse:
    return _download(key, connection, account)
