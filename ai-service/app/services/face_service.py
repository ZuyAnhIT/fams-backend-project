from __future__ import annotations

import io
import logging

import face_recognition
import numpy as np
from PIL import Image

logger = logging.getLogger(__name__)


def extract_embedding(image_bytes: bytes) -> list[float]:
    """Extract 128-dim face embedding. Raises ValueError if not exactly one face."""
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img_array = np.array(img)

    locations = face_recognition.face_locations(img_array)
    if len(locations) == 0:
        raise ValueError("no_face_detected")
    if len(locations) > 1:
        raise ValueError("multiple_faces_detected")

    encodings = face_recognition.face_encodings(img_array, locations)
    return encodings[0].tolist()


def average_embeddings(embeddings: list[list[float]]) -> list[float]:
    """Element-wise mean of multiple 128-dim embedding vectors."""
    return np.array(embeddings).mean(axis=0).tolist()


def cosine_similarity(vec_a: list[float], vec_b: list[float]) -> float:
    a = np.array(vec_a)
    b = np.array(vec_b)
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    if denom == 0:
        return 0.0
    return float(np.dot(a, b) / denom)
