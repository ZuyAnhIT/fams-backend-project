from __future__ import annotations

import logging
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import Response

from app.config import STORAGE_BASE_PATH
from app.dependencies import verify_internal_secret
from app.services import storage_service

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(verify_internal_secret)])


@router.get("/checkins/{source_id}/photo")
def get_checkin_photo(source_id: str, tenant_id: str = Query(...)):
    """The selfie submitted for a checkin/checkout or random-check response — worker.py's
    save_checkin_photo() writes every such photo to checkins/{tenant_id}/{source_id}.jpg
    unconditionally (source_id is the CheckinRecord id or CheckResponse id, matching whatever
    Java passed as the job's source_id). Unlike /enroll/{employee_id}/pending-photo, there's no
    DB row to look the path up through — the filename IS the source_id — so this is a direct
    file existence check. Permission/site-scope is enforced by the Java layer before this
    internal call is made, same as every other endpoint in this service."""
    file_path = Path(STORAGE_BASE_PATH) / "checkins" / tenant_id / f"{source_id}.jpg"
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="no photo found for this source_id")

    return Response(content=storage_service.read_photo(str(file_path)), media_type="image/jpeg")
