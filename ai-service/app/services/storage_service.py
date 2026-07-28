from __future__ import annotations

import shutil
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
