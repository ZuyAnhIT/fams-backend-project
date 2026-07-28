from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, HTTPException

from app.db import get_conn, put_conn
from app.dependencies import verify_internal_secret

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(verify_internal_secret)])


@router.delete("/embeddings/{profile_id}")
def delete_embedding(profile_id: str) -> dict:
    """Self-healing counterpart to DELETE /enroll/{employee_id}: called by the weekly
    DataRetentionJob for any already-revoked face_profiles row whose embedding somehow
    survived the synchronous revoke call (e.g. that call timed out/failed at the time).
    Idempotent — safe to call on a profile whose embedding is already NULL."""
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE face_profiles
                SET embedding = NULL, embedding_deleted = true, updated_at = now()
                WHERE id = %s::uuid
                """,
                [profile_id],
            )
            if cur.rowcount == 0:
                raise HTTPException(status_code=404, detail="face profile not found")
        conn.commit()
    except HTTPException:
        conn.rollback()
        raise
    except Exception as exc:
        conn.rollback()
        logger.error("DB embedding delete failed profile_id=%s: %s", profile_id, exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Embedding purged profile_id=%s", profile_id)
    return {"status": "embedding_deleted"}
