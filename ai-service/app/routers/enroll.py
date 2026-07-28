from __future__ import annotations

import logging
from itertools import combinations
from typing import List

from fastapi import APIRouter, Depends, Form, HTTPException, Query, UploadFile

from app import config
from app.db import get_conn, put_conn
from app.dependencies import verify_internal_secret
from app.services import face_service, storage_service
from app.services.liveness_service import check_liveness

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(verify_internal_secret)])


@router.post("/enroll")
async def enroll_face(
    tenant_id: str = Form(...),
    employee_id: str = Form(...),
    photos: List[UploadFile] = ...,
) -> dict:
    """Submit an enrollment (first-time or re-enrollment) batch for HR review.

    Never activates a face profile directly — every batch that passes the automated checks
    below lands in `pending_embedding` / `review_status='pending'` and waits for a human
    (HR/Admin) to approve it via POST /enroll/{employee_id}/approve. This is deliberate: the
    automated checks below only rule out the crudest fraud (printed photo, screen replay,
    mismatched people across the batch) — they cannot confirm the person in the photos is who
    they claim to be, so a human still has to make that call, exactly as most biometric
    time-and-attendance systems require (unlike a one-off eKYC flow, there is no external ID
    document here to auto-match against).

    If the employee already has an 'enrolled' profile, that profile's `embedding` is left
    untouched while this new batch is in review — they keep checking in with their currently
    approved face until/unless this new batch gets approved.
    """
    n = len(photos)
    if not (config.AI_ENROLL_MIN_PHOTOS <= n <= config.AI_ENROLL_MAX_PHOTOS):
        raise HTTPException(
            status_code=400,
            detail=f"Expected {config.AI_ENROLL_MIN_PHOTOS}-{config.AI_ENROLL_MAX_PHOTOS} photos, got {n}",
        )

    embeddings: list[list[float]] = []
    photo_bytes_list: list[bytes] = []

    for idx, photo in enumerate(photos):
        data = await photo.read()

        try:
            is_live, antispoof_score = check_liveness(data)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=f"photo {idx + 1}: {exc}")
        except Exception as exc:
            logger.error("Liveness check failed during enroll employee_id=%s: %s", employee_id, exc)
            raise HTTPException(status_code=400, detail=f"photo {idx + 1}: liveness_check_error")
        if not is_live:
            raise HTTPException(
                status_code=400,
                detail=f"photo {idx + 1}: failed anti-spoofing check (antispoof_score={antispoof_score:.2f}) "
                       "— looks like a photo of a photo/screen, not a live face. Retake with better lighting, "
                       "facing the camera directly.",
            )

        try:
            emb = face_service.extract_embedding(data)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=f"photo {idx + 1}: {exc}")
        embeddings.append(emb)
        photo_bytes_list.append(data)

    # All N photos in one enrollment batch must be the same person — otherwise the averaged
    # embedding is meaningless (or, worse, someone slipped in another person's photo).
    for (i, emb_a), (j, emb_b) in combinations(enumerate(embeddings), 2):
        sim = face_service.cosine_similarity(emb_a, emb_b)
        if sim < config.AI_ENROLL_SAME_PERSON_THRESHOLD:
            raise HTTPException(
                status_code=400,
                detail=f"photo {i + 1} and photo {j + 1} do not appear to be the same person "
                       f"(similarity={sim:.2f}) — retake all photos of the same person in one sitting.",
            )

    avg_embedding = face_service.average_embeddings(embeddings)

    saved_paths = [storage_service.save_enrollment_photo(tenant_id, employee_id, data)
                   for data in photo_bytes_list]
    # First photo is the representative frame HR sees on the review queue (GET
    # /enroll/{employee_id}/pending-photo) — arbitrary but consistent choice, avoids "duyệt mù"
    # (approving without ever seeing a photo).
    representative_photo_path = saved_paths[0]

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE face_profiles
                SET pending_embedding    = %s::double precision[],
                    pending_photo_count  = %s,
                    pending_photo_path   = %s,
                    review_status        = 'pending',
                    submitted_at         = now(),
                    rejection_reason     = NULL,
                    updated_at           = now()
                WHERE employee_id = %s::uuid AND tenant_id = %s::uuid
                """,
                [avg_embedding, n, representative_photo_path, employee_id, tenant_id],
            )
            if cur.rowcount == 0:
                raise HTTPException(status_code=404, detail="face profile not found — call POST /consent first")
        conn.commit()
    except HTTPException:
        conn.rollback()
        raise
    except Exception as exc:
        conn.rollback()
        logger.error("DB write failed employee_id=%s: %s", employee_id, exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Enrollment submitted for review employee_id=%s photo_count=%d", employee_id, n)
    return {"status": "pending", "photo_count": n}


@router.post("/enroll-from-challenge")
def enroll_from_challenge(
    tenant_id: str = Form(...),
    employee_id: str = Form(...),
    challenge_id: str = Form(...),
) -> dict:
    """Active-liveness enrollment path: promotes a PASSED 'enroll' challenge's already-verified
    embedding into pending_embedding for HR review — same review workflow as POST /enroll, just
    sourced from a challenge instead of raw photos. Single-use: the challenge is marked
    'consumed' so it can't be replayed into a second enrollment."""
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            # Atomic claim: UPDATE...WHERE status='passed' in the same statement that reads it,
            # so two concurrent requests replaying the same challenge_id can't both succeed —
            # the second sees rowcount=0 and fails cleanly instead of silently double-enrolling.
            cur.execute(
                "UPDATE liveness_challenges SET status = 'consumed', consumed_at = now() "
                "WHERE id = %s::uuid AND tenant_id = %s::uuid AND employee_id = %s::uuid "
                "AND purpose = 'enroll' AND status = 'passed' "
                "RETURNING embedding, center_frame_path",
                [challenge_id, tenant_id, employee_id],
            )
            row = cur.fetchone()
            if row is None:
                raise HTTPException(
                    status_code=409,
                    detail="challenge not found, not an 'enroll' challenge, not passed, or already consumed")
            embedding, center_frame_path = row

            cur.execute(
                """
                UPDATE face_profiles
                SET pending_embedding    = %s::double precision[],
                    pending_photo_count  = 3,
                    pending_photo_path   = %s,
                    review_status        = 'pending',
                    submitted_at         = now(),
                    rejection_reason     = NULL,
                    updated_at           = now()
                WHERE employee_id = %s::uuid AND tenant_id = %s::uuid
                """,
                [embedding, center_frame_path, employee_id, tenant_id],
            )
            if cur.rowcount == 0:
                raise HTTPException(status_code=404, detail="face profile not found — call POST /consent first")
        conn.commit()
    except HTTPException:
        conn.rollback()
        raise
    except Exception as exc:
        conn.rollback()
        logger.error("DB write failed employee_id=%s: %s", employee_id, exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Enrollment submitted for review (from challenge) employee_id=%s challenge_id=%s",
                employee_id, challenge_id)
    return {"status": "pending"}


@router.post("/enroll/{employee_id}/approve")
def approve_face(
    employee_id: str,
    tenant_id: str = Query(...),
) -> dict:
    """Promote the pending submission to the live, checkin-usable embedding."""
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE face_profiles
                SET embedding          = pending_embedding,
                    embedding_deleted  = false,
                    status             = 'enrolled',
                    enrolled_at        = now(),
                    review_status      = 'none',
                    pending_embedding  = NULL,
                    pending_photo_count = NULL,
                    pending_photo_path = NULL,
                    updated_at         = now()
                WHERE employee_id = %s::uuid AND tenant_id = %s::uuid AND review_status = 'pending'
                """,
                [employee_id, tenant_id],
            )
            if cur.rowcount == 0:
                raise HTTPException(status_code=404, detail="no pending enrollment for this employee")
        conn.commit()
    except HTTPException:
        conn.rollback()
        raise
    except Exception as exc:
        conn.rollback()
        logger.error("DB approve failed employee_id=%s: %s", employee_id, exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Enrollment approved employee_id=%s", employee_id)
    return {"status": "enrolled"}


@router.post("/enroll/{employee_id}/reject")
def reject_face(
    employee_id: str,
    tenant_id: str = Query(...),
    reason: str = Form(...),
) -> dict:
    """Discard the pending submission. Any previously-approved embedding (if this was a
    re-enrollment attempt) is left untouched — the employee keeps checking in as before and
    must submit a fresh batch."""
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE face_profiles
                SET review_status       = 'rejected',
                    rejection_reason    = %s,
                    pending_embedding   = NULL,
                    pending_photo_count = NULL,
                    pending_photo_path  = NULL,
                    updated_at          = now()
                WHERE employee_id = %s::uuid AND tenant_id = %s::uuid AND review_status = 'pending'
                """,
                [reason, employee_id, tenant_id],
            )
            if cur.rowcount == 0:
                raise HTTPException(status_code=404, detail="no pending enrollment for this employee")
        conn.commit()
    except HTTPException:
        conn.rollback()
        raise
    except Exception as exc:
        conn.rollback()
        logger.error("DB reject failed employee_id=%s: %s", employee_id, exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Enrollment rejected employee_id=%s reason=%s", employee_id, reason)
    return {"status": "rejected"}


@router.get("/enroll/{employee_id}/pending-photo")
def get_pending_photo(
    employee_id: str,
    tenant_id: str = Query(...),
):
    """The representative reference photo for a PENDING submission — lets HR actually see who
    they're approving instead of clicking Approve/Reject blind (site-scope/permission already
    enforced by the Java layer before this internal call is made)."""
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT pending_photo_path FROM face_profiles "
                "WHERE employee_id = %s::uuid AND tenant_id = %s::uuid AND review_status = 'pending'",
                [employee_id, tenant_id],
            )
            row = cur.fetchone()
    finally:
        put_conn(conn)

    if row is None or not row[0]:
        raise HTTPException(status_code=404, detail="no pending photo for this employee")

    from fastapi.responses import Response
    return Response(content=storage_service.read_photo(row[0]), media_type="image/jpeg")


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
                SET embedding           = NULL,
                    embedding_deleted   = true,
                    status              = 'revoked',
                    revoked_at          = now(),
                    review_status       = 'none',
                    pending_embedding   = NULL,
                    pending_photo_count = NULL,
                    rejection_reason    = NULL,
                    updated_at          = now()
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
