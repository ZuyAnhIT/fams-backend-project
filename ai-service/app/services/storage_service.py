from __future__ import annotations

import shutil
import time
import uuid
from pathlib import Path

from app.config import STORAGE_BASE_PATH


def save_enrollment_photo(tenant_id: str, employee_id: str, image_bytes: bytes) -> str:
    """Save an enrollment photo and return its absolute path."""
    dir_path = Path(STORAGE_BASE_PATH) / "enrollments" / tenant_id / employee_id
    dir_path.mkdir(parents=True, exist_ok=True)
    file_path = dir_path / f"{uuid.uuid4()}.jpg"
    file_path.write_bytes(image_bytes)
    return str(file_path)


def delete_enrollment_photos(tenant_id: str, employee_id: str) -> None:
    """Delete all enrollment photos for an employee."""
    dir_path = Path(STORAGE_BASE_PATH) / "enrollments" / tenant_id / employee_id
    if dir_path.exists():
        shutil.rmtree(dir_path)


def save_checkin_photo(tenant_id: str, source_id: str, image_bytes: bytes) -> str:
    """Save a check-in/check-response selfie and return its absolute path."""
    dir_path = Path(STORAGE_BASE_PATH) / "checkins" / tenant_id
    dir_path.mkdir(parents=True, exist_ok=True)
    file_path = dir_path / f"{source_id}.jpg"
    file_path.write_bytes(image_bytes)
    return str(file_path)


def save_challenge_frame(tenant_id: str, challenge_id: str, image_bytes: bytes) -> str:
    """Save the accepted 'center' frame of a PASSED liveness challenge — consumed later by
    enroll (as the reference photo) or by the checkin worker (as the submitted selfie)."""
    dir_path = Path(STORAGE_BASE_PATH) / "liveness_challenges" / tenant_id
    dir_path.mkdir(parents=True, exist_ok=True)
    file_path = dir_path / f"{challenge_id}.jpg"
    file_path.write_bytes(image_bytes)
    return str(file_path)


def read_photo(path: str) -> bytes:
    return Path(path).read_bytes()


def delete_files_older_than(root_dirname: str, older_than_days: int) -> int:
    """Age-based retention sweep for one top-level storage directory (e.g. "checkins" or
    "liveness_challenges") — walks every file under STORAGE_BASE_PATH/{root_dirname}/**
    recursively and deletes any whose mtime is older than older_than_days. Returns the count
    deleted. Pure filesystem-mtime based, no DB lookup — safe for checkins/ and
    liveness_challenges/ since neither has a "pending, still needed" lifecycle state once
    written (unlike enrollments/, deliberately NOT swept here — see DataRetentionJob's Java-side
    comment for why enrollment photos need DB-aware handling instead)."""
    root = Path(STORAGE_BASE_PATH) / root_dirname
    if not root.exists():
        return 0

    cutoff = time.time() - older_than_days * 86400
    deleted = 0
    for file_path in root.rglob("*.jpg"):
        try:
            if file_path.stat().st_mtime < cutoff:
                file_path.unlink()
                deleted += 1
        except FileNotFoundError:
            pass  # raced with another sweep/request — fine, already gone
    return deleted
