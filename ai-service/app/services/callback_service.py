from __future__ import annotations

import logging

import httpx

from app.config import AI_INTERNAL_SECRET, JAVA_API_INTERNAL_URL

logger = logging.getLogger(__name__)

_CALLBACK_PATH = "/internal/ai-callback/face-result"
_TIMEOUT = 10.0


def send_face_result(
    source_id: str,
    tenant_id: str,
    source_type: str,
    face_verified: bool,
    liveness_verified: bool | None,
    face_verify_score: float | None,
    error_code: str | None,
) -> None:
    payload = {
        "sourceId": source_id,
        "tenantId": tenant_id,
        "sourceType": source_type,
        "faceVerified": face_verified,
        "livenessVerified": liveness_verified,
        "faceVerifyScore": face_verify_score,
        "errorCode": error_code,
    }
    url = f"{JAVA_API_INTERNAL_URL}{_CALLBACK_PATH}"
    try:
        with httpx.Client(timeout=_TIMEOUT) as client:
            resp = client.post(
                url,
                json=payload,
                headers={"X-Internal-Secret": AI_INTERNAL_SECRET},
            )
            if resp.status_code != 200:
                logger.error(
                    "Callback failed: sourceId=%s status=%d body=%s",
                    source_id,
                    resp.status_code,
                    resp.text[:200],
                )
            else:
                logger.info(
                    "Callback sent: sourceId=%s faceVerified=%s errorCode=%s",
                    source_id,
                    face_verified,
                    error_code,
                )
    except Exception as e:
        logger.error("Callback exception: sourceId=%s error=%s", source_id, e)
