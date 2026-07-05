from __future__ import annotations

import base64
import json
import logging
import threading
import traceback

from app.config import AI_FACE_SIMILARITY_THRESHOLD
from app.db import get_conn, put_conn
from app.services.callback_service import send_face_result
from app.services.face_service import cosine_similarity, extract_embedding
from app.services.storage_service import save_checkin_photo

logger = logging.getLogger(__name__)

QUEUE_KEY = "fams:ai:face_verify_jobs"


def _process_job(job_data: dict) -> None:
    source_id: str = job_data["source_id"]
    source_type: str = job_data["source_type"]
    tenant_id: str = job_data["tenant_id"]
    employee_id: str = job_data["employee_id"]
    face_image_base64: str = job_data["face_image_base64"]
    requires_liveness: bool = job_data.get("requires_liveness", False)

    try:
        image_bytes = base64.b64decode(face_image_base64)
    except Exception:
        send_face_result(source_id, tenant_id, source_type, False, None, None, "invalid_image")
        return

    save_checkin_photo(tenant_id, source_id, image_bytes)

    liveness_verified: bool | None = None
    liveness_score: float | None = None

    if requires_liveness:
        try:
            from app.services.liveness_service import check_liveness
            is_live, score = check_liveness(image_bytes)
            liveness_verified = is_live
            liveness_score = score
            if not is_live:
                send_face_result(source_id, tenant_id, source_type, False, liveness_verified, liveness_score, "liveness_failed")
                return
        except Exception as e:
            logger.error("Liveness check failed for sourceId=%s: %s", source_id, e)
            send_face_result(source_id, tenant_id, source_type, False, False, None, "liveness_error")
            return

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT embedding FROM face_profiles "
                "WHERE employee_id = %s::uuid AND tenant_id = %s::uuid AND status = 'enrolled'",
                (employee_id, tenant_id),
            )
            row = cur.fetchone()
    finally:
        put_conn(conn)

    if row is None or row[0] is None:
        send_face_result(source_id, tenant_id, source_type, False, liveness_verified, None, "no_profile")
        return

    stored_embedding: list[float] = row[0]

    try:
        checkin_embedding = extract_embedding(image_bytes)
    except ValueError as e:
        send_face_result(source_id, tenant_id, source_type, False, liveness_verified, None, str(e))
        return
    except Exception as e:
        logger.error("Embedding extraction failed for sourceId=%s: %s", source_id, e)
        send_face_result(source_id, tenant_id, source_type, False, liveness_verified, None, "extraction_error")
        return

    score = cosine_similarity(stored_embedding, checkin_embedding)
    face_verified = score >= AI_FACE_SIMILARITY_THRESHOLD

    send_face_result(source_id, tenant_id, source_type, face_verified, liveness_verified, score, None)


def _run_worker(redis_client) -> None:
    logger.info("Face verify worker started, listening on %s", QUEUE_KEY)
    while True:
        try:
            result = redis_client.brpop(QUEUE_KEY, timeout=5)
            if result is None:
                continue
            _, raw = result
            job_data = json.loads(raw)
            logger.info(
                "Processing face verify job: sourceType=%s sourceId=%s",
                job_data.get("source_type"),
                job_data.get("source_id"),
            )
            _process_job(job_data)
        except Exception:
            logger.error("Worker error:\n%s", traceback.format_exc())


def start_worker(redis_client) -> None:
    thread = threading.Thread(
        target=_run_worker,
        args=(redis_client,),
        daemon=True,
        name="face-verify-worker",
    )
    thread.start()
    logger.info("Face verify worker thread started")
