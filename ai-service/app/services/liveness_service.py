from __future__ import annotations

import io
import logging

import numpy as np
from deepface import DeepFace
from PIL import Image

logger = logging.getLogger(__name__)


def check_liveness(image_bytes: bytes) -> tuple[bool, float]:
    """Run MiniFASNet anti-spoofing. Returns (is_live, antispoof_score)."""
    img = np.array(Image.open(io.BytesIO(image_bytes)).convert("RGB"))
    faces = DeepFace.extract_faces(
        img_path=img,
        anti_spoofing=True,
        enforce_detection=True,
    )
    if not faces:
        raise ValueError("no_face_detected")

    face = faces[0]
    is_real: bool = bool(face.get("is_real", False))
    antispoof_score: float = float(face.get("antispoof_score", 0.0))
    return is_real, antispoof_score
