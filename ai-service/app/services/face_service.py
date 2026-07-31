from __future__ import annotations

import io
import logging

import insightface
import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)

_face_app: insightface.app.FaceAnalysis | None = None


def get_face_app() -> insightface.app.FaceAnalysis:
    """Lazily-initialized singleton — the InsightFace 'buffalo_l' model pack (SCRFD detector,
    ArcFace recognizer, 106/68-point landmark models, ~280MB) loads once and is reused for every
    request. `app.main.lifespan` warms this at startup so the first real request isn't slow;
    this lazy-init is just a safety net for any code path that runs before that (e.g. tests)."""
    global _face_app
    if _face_app is None:
        _face_app = insightface.app.FaceAnalysis(name="buffalo_l", providers=["CPUExecutionProvider"])
        _face_app.prepare(ctx_id=-1, det_size=(640, 640))
        logger.info("InsightFace buffalo_l ready (SCRFD detect + ArcFace recognize + 106/68pt landmarks + pose)")
    return _face_app


def _decode_bgr(image_bytes: bytes) -> np.ndarray:
    img_rgb = np.array(Image.open(io.BytesIO(image_bytes)).convert("RGB"))
    return img_rgb[:, :, ::-1]  # InsightFace (via OpenCV) expects BGR


def detect_single_face(image_bytes: bytes) -> insightface.app.common.Face:
    """Returns the one InsightFace Face object detected in this image — carries bbox,
    normed_embedding (512-dim ArcFace), landmark_2d_106, landmark_3d_68, and pose (pitch, yaw,
    roll) all in one pass, since SCRFD + the landmark models run together in FaceAnalysis.get().
    Raises ValueError('no_face_detected' | 'multiple_faces_detected') — same contract every
    caller in this service already expects from the old dlib-based detect step."""
    img_bgr = _decode_bgr(image_bytes)
    faces = get_face_app().get(img_bgr)
    if len(faces) == 0:
        raise ValueError("no_face_detected")
    if len(faces) > 1:
        raise ValueError("multiple_faces_detected")
    return faces[0]


def extract_embedding(image_bytes: bytes) -> list[float]:
    """Extract the 512-dim ArcFace face embedding (L2-normalized). Raises ValueError if not
    exactly one face — same contract as before, now backed by ArcFace (w600k_r50) instead of
    dlib's ResNet-34, which is meaningfully more accurate/discriminative for identity matching."""
    face = detect_single_face(image_bytes)
    return face.normed_embedding.tolist()


def average_embeddings(embeddings: list[list[float]]) -> list[float]:
    """Element-wise mean of multiple embedding vectors — unchanged, dimension-agnostic."""
    return np.array(embeddings).mean(axis=0).tolist()


def cosine_similarity(vec_a: list[float], vec_b: list[float]) -> float:
    a = np.array(vec_a)
    b = np.array(vec_b)
    if a.shape != b.shape:
        # Can only happen comparing an embedding stored under the OLD dlib model (128-dim)
        # against one extracted under ArcFace (512-dim) — i.e. an employee enrolled before this
        # switch. Raise instead of letting np.dot blow up with an opaque shape-mismatch
        # traceback, so callers (worker.py) can report a clear, actionable reason instead of
        # silently dropping the verification job. See docs/api/face-id-management-api.md.
        raise ValueError("embedding_dimension_mismatch")
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    if denom == 0:
        return 0.0
    return float(np.dot(a, b) / denom)
