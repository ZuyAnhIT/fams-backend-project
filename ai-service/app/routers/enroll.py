from __future__ import annotations

import logging
from typing import List

from fastapi import APIRouter, Depends, Form, HTTPException, Query, UploadFile

from app import config
from app.db import get_conn, put_conn
from app.dependencies import verify_internal_secret
from app.services import face_service, storage_service

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(verify_internal_secret)])


@router.post("/enroll")
async def enroll_face(
    tenant_id: str = Form(...),
    employee_id: str = Form(...),
    photos: List[UploadFile] = ...,
) -> dict:
    n = len(photos)
    if not (config.AI_ENROLL_MIN_PHOTOS <= n <= config.AI_ENROLL_MAX_PHOTOS):
        raise HTTPException(
            status_code=400,
            detail=f"Expected {config.AI_ENROLL_MIN_PHOTOS}-{config.AI_ENROLL_MAX_PHOTOS} photos, got {n}",
        )

    embeddings: list[list[float]] = []
    photo_bytes_list: list[bytes] = []

    for photo in photos:
        data = await photo.read()
        try:
            emb = face_service.extract_embedding(data)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc))
        embeddings.append(emb)
        photo_bytes_list.append(data)

    avg_embedding = face_service.average_embeddings(embeddings)

    for data in photo_bytes_list:
        storage_service.save_enrollment_photo(tenant_id, employee_id, data)

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE face_profiles
                SET embedding    = %s::double precision[],
                    status       = 'enrolled',
                    enrolled_at  = now(),
                    updated_at   = now()
                WHERE employee_id = %s::uuid
                """,
                [avg_embedding, employee_id],
            )
        conn.commit()
    except Exception as exc:
        conn.rollback()
        logger.error("DB write failed employee_id=%s: %s", employee_id, exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Enrolled employee_id=%s photo_count=%d", employee_id, n)
    return {"status": "enrolled", "photo_count": n}


@router.delete("/enroll/{employee_id}")
def revoke_face(
    employee_id: str,
    tenant_id: str = Query(...),
) -> dict:
    storage_service.delete_enrollment_photos(tenant_id, employee_id)

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE face_profiles
                SET embedding   = NULL,
                    status      = 'revoked',
                    revoked_at  = now(),
                    updated_at  = now()
                WHERE employee_id = %s::uuid
                """,
                [employee_id],
            )
        conn.commit()
    except Exception as exc:
        conn.rollback()
        logger.error("DB revoke failed employee_id=%s: %s", employee_id, exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Revoked face profile employee_id=%s", employee_id)
    return {"status": "revoked"}
