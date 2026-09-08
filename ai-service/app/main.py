from __future__ import annotations

import logging
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI

from app.routers import checkin_photo, embeddings, enroll, health, liveness_challenge, status

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)


def _load_insightface() -> None:
    from app.services.face_service import get_face_app

    get_face_app()  # loads the buffalo_l pack (SCRFD + ArcFace + landmarks) once, up front


def _load_liveness_model() -> None:
    from deepface import DeepFace

    # Realistic-sized (640×480) instead of the old 100×100 blank so the whole ONNX graph — not
    # just the tiny-input fast-path — is JIT-compiled and its intermediate buffers get allocated
    # at startup. Detection still fails (uniform noise, no face), which is fine; we only care
    # that the model's memory pages are touched now rather than on the first real request.
    img = np.random.randint(0, 255, size=(480, 640, 3), dtype=np.uint8)
    try:
        DeepFace.extract_faces(img_path=img, anti_spoofing=True, enforce_detection=False)
    except Exception:
        pass  # detection fail is expected — the model warm-up is the goal
    logger.info("deepface FasNet (MiniFASNetV2 + MiniFASNetV1SE) liveness model ready")


def _warm_with_fixture_if_available() -> None:
    """If a warmup fixture is already persisted from a prior boot, run one real inference cycle
    NOW so the first user of the day never hits the cold path — before we even start accepting
    requests."""
    from app.services.warmup import get_warmup_image, run_warm_once
    if get_warmup_image() is None:
        return
    result = run_warm_once()
    logger.info("Startup warmup against persisted fixture: %s", result)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Loading AI models...")
    _load_insightface()
    _load_liveness_model()

    from app.services.warmup import start_warmup_thread
    start_warmup_thread()
    _warm_with_fixture_if_available()

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
app.include_router(embeddings.router)
app.include_router(liveness_challenge.router)
app.include_router(checkin_photo.router)
