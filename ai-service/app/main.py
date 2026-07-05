from __future__ import annotations

import logging
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI

from app.routers import enroll, health, status

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def _load_face_recognition() -> None:
    import face_recognition  # noqa: F401 — imports bundled dlib ResNet-34 weights

    logger.info("face_recognition (dlib ResNet-34) ready")


def _load_liveness_model() -> None:
    from deepface import DeepFace

    img = np.zeros((100, 100, 3), dtype=np.uint8)
    try:
        DeepFace.extract_faces(img_path=img, anti_spoofing=True, enforce_detection=False)
    except Exception:
        pass  # blank image always fails detection — model is now warm
    logger.info("deepface FasNet (MiniFASNetV2 + MiniFASNetV1SE) liveness model ready")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Loading AI models...")
    _load_face_recognition()
    _load_liveness_model()

    from app.redis_client import get_redis
    from app.worker import start_worker
    start_worker(get_redis())

    logger.info("All models ready — FAMS AI service is up")
    yield
    logger.info("FAMS AI service shutting down")


app = FastAPI(title="FAMS AI Service", version="1.0.0", lifespan=lifespan)

app.include_router(health.router)
app.include_router(enroll.router)
app.include_router(status.router)
