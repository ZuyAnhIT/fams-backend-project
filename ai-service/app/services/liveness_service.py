from __future__ import annotations

import logging

import cv2
import numpy as np
from deepface import DeepFace

logger = logging.getLogger(__name__)


def check_liveness(image_bytes: bytes) -> tuple[bool, float]:
    """DeepFace MiniFASNet anti-spoofing. Returns (is_live, antispoof_score).

    Raises ValueError('no_face_detected') if no face is found — same contract
    expected by all three callers (enroll, liveness_challenge, worker).
    """
    img_array = np.frombuffer(image_bytes, np.uint8)
    img_bgr = cv2.imdecode(img_array, cv2.IMREAD_COLOR)

    try:
        faces = DeepFace.extract_faces(
            img_path=img_bgr,
            anti_spoofing=True,
            enforce_detection=True,
        )
    except ValueError as exc:
        msg = str(exc).lower()
        if "face could not be detected" in msg or "no face" in msg:
            raise ValueError("no_face_detected") from exc
        raise

    if not faces:
        raise ValueError("no_face_detected")

    face = faces[0]
    is_real: bool = bool(face.get("is_real", False))
    antispoof_score: float = float(face.get("antispoof_score", 0.0))
    return is_real, antispoof_score
