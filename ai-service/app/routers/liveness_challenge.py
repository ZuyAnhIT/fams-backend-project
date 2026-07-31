from __future__ import annotations

import itertools
import json
import logging
import random
from typing import List

from fastapi import APIRouter, Depends, Form, HTTPException, UploadFile

from app import config
from app.db import get_conn, put_conn
from app.dependencies import verify_internal_secret
from app.services import face_service, storage_service
from app.services.head_pose_service import classify_frame, estimate_baseline_pose
from app.services.liveness_service import check_liveness

logger = logging.getLogger(__name__)

router = APIRouter(dependencies=[Depends(verify_internal_secret)])

# look_up/look_down restored to the pool (2026-07-31) — the previous removal was a workaround for
# dlib 6-point + solvePnP's systematic pitch bias, not a limitation of the action itself. Pose
# now comes from InsightFace's landmark_3d_68 model via a 3D similarity transform against a
# canonical mean face (see head_pose_service.py) — fundamentally more accurate, verified
# numerically against this service's test fixture (near-zero pitch/yaw on a frontal photo,
# unlike the old pipeline's large systematic offset). Still recommend real-device QA before
# relying on this at scale — this environment has no camera to validate that directly.
_ACTION_POOL = ["turn_left", "turn_right", "look_up", "look_down", "blink"]
_CHALLENGE_TTL_SECONDS = 90
_SAME_PERSON_THRESHOLD = 0.45


@router.post("/liveness-challenge")
def start_challenge(
    tenant_id: str = Form(...),
    employee_id: str = Form(...),
    purpose: str = Form(...),
    site_id: str | None = Form(None),
) -> dict:
    if purpose not in ("enroll", "checkin", "checkout"):
        raise HTTPException(status_code=400, detail="purpose must be 'enroll', 'checkin', or 'checkout'")

    # Always starts with 'center' (the frontal reference frame used for the actual embedding +
    # single-frame anti-spoofing), followed by 2 randomly chosen, non-repeating dynamic actions
    # — random per session so a pre-recorded video of someone else's challenge can't be replayed.
    actions = ["center"] + random.sample(_ACTION_POOL, 2)

    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO liveness_challenges (tenant_id, employee_id, purpose, actions, site_id, expires_at)
                VALUES (%s::uuid, %s::uuid, %s, %s, %s::uuid, now() + (%s || ' seconds')::interval)
                RETURNING id, expires_at
                """,
                [tenant_id, employee_id, purpose, actions, site_id, _CHALLENGE_TTL_SECONDS],
            )
            challenge_id, expires_at = cur.fetchone()
        conn.commit()
    except Exception as exc:
        conn.rollback()
        logger.error("Failed to create liveness challenge: %s", exc)
        raise HTTPException(status_code=500, detail="DB write failed")
    finally:
        put_conn(conn)

    logger.info("Liveness challenge started: id=%s employee_id=%s actions=%s", challenge_id, employee_id, actions)
    return {"challengeId": str(challenge_id), "actions": actions, "expiresAt": expires_at.isoformat()}


@router.post("/liveness-challenge/{challenge_id}/frames")
async def submit_frames(
    challenge_id: str,
    tenant_id: str = Form(...),
    employee_id: str = Form(...),
    frames: List[UploadFile] = ...,
) -> dict:
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT actions, status, expires_at FROM liveness_challenges "
                "WHERE id = %s::uuid AND tenant_id = %s::uuid AND employee_id = %s::uuid",
                [challenge_id, tenant_id, employee_id],
            )
            row = cur.fetchone()
    finally:
        put_conn(conn)

    if row is None:
        raise HTTPException(status_code=404, detail="challenge not found")
    actions, status, expires_at = row
    if status != "pending":
        raise HTTPException(status_code=409, detail=f"challenge already {status}")

    from datetime import datetime, timezone
    if datetime.now(timezone.utc) > expires_at:
        _finish(challenge_id, "expired", {"reason": "expired_before_submit"})
        raise HTTPException(status_code=409, detail="challenge expired")

    if len(frames) != len(actions):
        raise HTTPException(
            status_code=400,
            detail=f"expected {len(actions)} frames (one per action {actions}), got {len(frames)}")

    frame_bytes_list: list[bytes] = [await photo.read() for photo in frames]

    # Establish a session-relative pose baseline from the 'center' frame BEFORE classifying any
    # frame — see estimate_baseline_pose's docstring for why: an assumed (0, 0) baseline doesn't
    # match how a phone is actually held, causing systematic false-rejects. Falls back to (0, 0)
    # if the center frame is unreadable.
    baseline_pitch, baseline_yaw = 0.0, 0.0
    try:
        center_idx = actions.index("center")
        center_face = face_service.detect_single_face(frame_bytes_list[center_idx])
        baseline = estimate_baseline_pose(center_face)
        if baseline is not None:
            baseline_pitch, baseline_yaw = baseline
    except Exception as exc:
        logger.warning("Could not establish pose baseline for challenge %s, falling back to "
                        "absolute (0, 0): %s", challenge_id, exc)

    embeddings: list[list[float]] = []
    steps: list[dict] = []
    center_frame_bytes: bytes | None = None
    failure_reason: str | None = None

    for i, (data, expected_action) in enumerate(zip(frame_bytes_list, actions)):
        try:
            face = face_service.detect_single_face(data)
        except ValueError as exc:
            steps.append({"action": expected_action, "passed": False, "reason": str(exc)})
            failure_reason = failure_reason or f"frame {i + 1}: {exc}"
            continue
        except Exception:
            steps.append({"action": expected_action, "passed": False, "reason": "invalid_image"})
            failure_reason = failure_reason or f"frame {i + 1}: invalid_image"
            continue

        satisfied = classify_frame(face, baseline_pitch, baseline_yaw)
        passed = expected_action in satisfied
        steps.append({"action": expected_action, "passed": passed, "detected": sorted(satisfied)})
        if not passed:
            failure_reason = failure_reason or (
                f"frame {i + 1}: expected '{expected_action}', detected {sorted(satisfied) or ['none']}")
            continue

        # InsightFace's detect_single_face already computed the embedding in this same pass —
        # no need for a second, redundant detection call like the old dlib pipeline needed.
        embeddings.append(face.normed_embedding.tolist())

        if expected_action == "center":
            center_frame_bytes = data

    if failure_reason is None:
        # All frames individually satisfied their expected action AND yielded an embedding —
        # now the two whole-batch checks: same person across every frame (catches someone
        # splicing in a different person's blink/turn frame), and anti-spoofing on the center
        # frame (catches a well-lit printed photo that could otherwise pass pose/blink checks).
        for (i, emb_a), (j, emb_b) in itertools.combinations(enumerate(embeddings), 2):
            sim = face_service.cosine_similarity(emb_a, emb_b)
            if sim < _SAME_PERSON_THRESHOLD:
                failure_reason = f"frames {i + 1} and {j + 1} do not appear to be the same person (similarity={sim:.2f})"
                break

    if failure_reason is None and center_frame_bytes is not None:
        try:
            is_live, antispoof_score = check_liveness(center_frame_bytes)
            steps.append({"action": "anti_spoof_check", "passed": is_live, "score": antispoof_score})
            if not is_live:
                failure_reason = f"center frame failed anti-spoofing (score={antispoof_score:.2f})"
        except Exception as exc:
            failure_reason = f"anti-spoofing check error: {exc}"

    if failure_reason is not None:
        _finish(challenge_id, "failed", {"steps": steps, "reason": failure_reason})
        logger.info("Liveness challenge failed: id=%s reason=%s", challenge_id, failure_reason)
        return {"status": "failed", "reason": failure_reason, "steps": steps}

    avg_embedding = face_service.average_embeddings(embeddings)
    frame_path = storage_service.save_challenge_frame(tenant_id, challenge_id, center_frame_bytes)
    _finish(challenge_id, "passed", {"steps": steps}, embedding=avg_embedding, center_frame_path=frame_path)
    logger.info("Liveness challenge passed: id=%s employee_id=%s", challenge_id, employee_id)
    return {"status": "passed", "steps": steps}


def _finish(challenge_id: str, status: str, result_detail: dict,
            embedding: list[float] | None = None, center_frame_path: str | None = None) -> None:
    conn = get_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE liveness_challenges
                SET status = %s, result_detail = %s::jsonb, completed_at = now(),
                    embedding = %s::double precision[], center_frame_path = %s
                WHERE id = %s::uuid
                """,
                [status, json.dumps(result_detail), embedding, center_frame_path, challenge_id],
            )
        conn.commit()
    except Exception as exc:
        conn.rollback()
        logger.error("Failed to finalize liveness challenge %s: %s", challenge_id, exc)
    finally:
        put_conn(conn)
