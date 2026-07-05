from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException, Query

from app.db import get_conn, put_conn
from app.dependencies import verify_internal_secret

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(verify_internal_secret)])


@router.get("/status/{employee_id}")
def get_status(
    employee_id: str,
    tenant_id: str = Query(...),
) -> dict:
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT status, consent_given, enrolled_at
                FROM face_profiles
                WHERE employee_id = %s::uuid AND tenant_id = %s::uuid
                """,
                [employee_id, tenant_id],
            )
            row = cur.fetchone()
    finally:
        put_conn(conn)

    if row is None:
        return {
            "employee_id": employee_id,
            "status": "not_enrolled",
            "enrolled_at": None,
            "consent_given": False,
        }

    status, consent_given, enrolled_at = row
    return {
        "employee_id": employee_id,
        "status": status,
        "enrolled_at": enrolled_at.isoformat() if enrolled_at else None,
        "consent_given": consent_given,
    }
