from __future__ import annotations

from fastapi import APIRouter

from app.services.warmup import run_warm_once

router = APIRouter()


@router.get("/health")
def health() -> dict:
    return {"status": "ok"}


@router.get("/health/warm")
def health_warm() -> dict:
    """On-demand model-warmup trigger. The background thread in warmup.py fires this every few
    minutes anyway; this endpoint is here for manual re-warming and for monitoring elapsed_ms."""
    return run_warm_once()
