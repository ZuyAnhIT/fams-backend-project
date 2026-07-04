from __future__ import annotations

import logging
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

_face_recognition_ready = False
_liveness_ready = False


def _load_face_recognition() -> None:
    global _face_recognition_ready
    import face_recognition  # noqa: F401 — imports bundled dlib ResNet-34 weights
    _face_recognition_ready = True
    logger.info("face_recognition (dlib ResNet-34) ready")


def _load_liveness_model() -> None:
    global _liveness_ready
    from deepface import DeepFace

    img = np.zeros((100, 100, 3), dtype=np.uint8)
    try:
        DeepFace.extract_faces(img_path=img, anti_spoofing=True, enforce_detection=False)
    except Exception:
        pass  # blank image always fails detection — that's expected; model is now warm
    _liveness_ready = True
    logger.info("deepface FasNet (MiniFASNetV2 + MiniFASNetV1SE) liveness model ready")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Loading AI models...")
    _load_face_recognition()
    _load_liveness_model()
    logger.info("All models ready — FAMS AI service is up")
    yield
    logger.info("FAMS AI service shutting down")


app = FastAPI(title="FAMS AI Service", version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health():
    return {
        "status": "ok",
        "models": {
            "face_recognition": _face_recognition_ready,
            "liveness_fasnet": _liveness_ready,
        },
    }
