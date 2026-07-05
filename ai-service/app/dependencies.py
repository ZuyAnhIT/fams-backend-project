from __future__ import annotations

from fastapi import Header, HTTPException

from app.config import AI_INTERNAL_SECRET


def verify_internal_secret(x_internal_secret: str = Header(...)) -> None:
    if x_internal_secret != AI_INTERNAL_SECRET:
        raise HTTPException(status_code=403, detail="Invalid internal secret")
