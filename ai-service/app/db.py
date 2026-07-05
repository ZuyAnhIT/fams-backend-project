from __future__ import annotations

import logging

import psycopg2
from psycopg2.pool import ThreadedConnectionPool

from app.config import DB_HOST, DB_NAME, DB_PASSWORD, DB_PORT, DB_USER

logger = logging.getLogger(__name__)

_pool: ThreadedConnectionPool | None = None


def get_pool() -> ThreadedConnectionPool:
    global _pool
    if _pool is None:
        _pool = ThreadedConnectionPool(
            minconn=1,
            maxconn=5,
            host=DB_HOST,
            port=DB_PORT,
            dbname=DB_NAME,
            user=DB_USER,
            password=DB_PASSWORD,
        )
        logger.info("DB connection pool created host=%s db=%s", DB_HOST, DB_NAME)
    return _pool


def get_conn() -> psycopg2.extensions.connection:
    return get_pool().getconn()


def put_conn(conn: psycopg2.extensions.connection) -> None:
    get_pool().putconn(conn)
