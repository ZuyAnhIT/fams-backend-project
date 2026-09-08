from __future__ import annotations

import logging
import threading
import time
from pathlib import Path

from app.config import STORAGE_BASE_PATH

logger = logging.getLogger(__name__)

# A known-good face image is cached in memory and periodically re-fed to InsightFace + DeepFace
# so their ONNX weight pages stay resident. Without this, ~30 min of idle is enough for the
# kernel to page out ~300MB of buffalo_l + MiniFASNet, and the next real request pays 50-70s of
# page-in cost sequentially across 4 detections + 1 anti-spoof — the exact cold-tax that made
# testers' first check-in of the day take 70-80s server-side.
_WARMUP_LOCK = threading.Lock()
_CACHED_FACE_BYTES: bytes | None = None
_FIXTURE_PATH = Path(STORAGE_BASE_PATH) / "warmup_fixture.jpg"

# 4 minutes — comfortably below the ~30 min idle threshold at which page eviction starts biting,
# short enough that even the first user of the day after an overnight lull finds the models hot.
_WARM_INTERVAL_SECONDS = 240


def _load_persisted_fixture() -> None:
    global _CACHED_FACE_BYTES
    if _FIXTURE_PATH.exists():
        try:
            _CACHED_FACE_BYTES = _FIXTURE_PATH.read_bytes()
            logger.info("Loaded persisted warmup fixture (%d bytes) from %s",
                        len(_CACHED_FACE_BYTES), _FIXTURE_PATH)
        except Exception as exc:
            logger.warning("Failed to load persisted warmup fixture: %s", exc)


def cache_warmup_image(image_bytes: bytes) -> None:
    """Bootstrap the warmup fixture from the first user photo that passes real detection. Idempotent
    — only the first successful call wins, subsequent enroll/checkin frames don't churn the fixture.
    Persisted to disk so container restarts don't re-pay the first-user cold tax."""
    global _CACHED_FACE_BYTES
    with _WARMUP_LOCK:
        if _CACHED_FACE_BYTES is not None:
            return
        _CACHED_FACE_BYTES = image_bytes
    try:
        _FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
        _FIXTURE_PATH.write_bytes(image_bytes)
        logger.info("Persisted warmup fixture to %s", _FIXTURE_PATH)
    except Exception as exc:
        logger.warning("Failed to persist warmup fixture: %s", exc)


def get_warmup_image() -> bytes | None:
    return _CACHED_FACE_BYTES


def run_warm_once() -> dict:
    """Runs one full detect + anti-spoof cycle against the cached fixture. Returns a small
    dict for the /health/warm endpoint. Never raises — a warm attempt failing is a monitoring
    signal, not a request failure."""
    from app.services import face_service
    from app.services.liveness_service import check_liveness

    image = _CACHED_FACE_BYTES
    if image is None:
        return {"status": "no_fixture_yet"}

    detected = False
    live_ok = False
    t0 = time.monotonic()
    try:
        face_service.detect_single_face(image)
        detected = True
    except Exception as exc:
        logger.debug("warm detect exception (ignored): %s", exc)
    try:
        check_liveness(image)
        live_ok = True
    except Exception as exc:
        logger.debug("warm liveness exception (ignored): %s", exc)
    elapsed_ms = int((time.monotonic() - t0) * 1000)
    return {"status": "warm", "detected": detected, "antispoof_ran": live_ok, "elapsed_ms": elapsed_ms}


def _warm_loop() -> None:
    while True:
        try:
            time.sleep(_WARM_INTERVAL_SECONDS)
            if _CACHED_FACE_BYTES is None:
                continue
            result = run_warm_once()
            logger.info("warmup cycle: %s", result)
        except Exception as exc:
            logger.warning("warmup loop error (continuing): %s", exc)


def start_warmup_thread() -> None:
    _load_persisted_fixture()
    thread = threading.Thread(target=_warm_loop, daemon=True, name="model-warmup")
    thread.start()
    logger.info("Model warmup thread started (interval=%ds)", _WARM_INTERVAL_SECONDS)
