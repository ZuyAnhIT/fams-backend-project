from __future__ import annotations

import redis

from app.config import REDIS_HOST, REDIS_PASSWORD, REDIS_PORT

_pool = redis.ConnectionPool(
    host=REDIS_HOST,
    port=REDIS_PORT,
    password=REDIS_PASSWORD if REDIS_PASSWORD else None,
    decode_responses=True,
    max_connections=5,
)


def get_redis() -> redis.Redis:
    return redis.Redis(connection_pool=_pool)
