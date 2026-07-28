from __future__ import annotations

import os

from dotenv import load_dotenv

load_dotenv()

DB_HOST: str = os.getenv("DB_HOST", "localhost")
DB_PORT: int = int(os.getenv("DB_PORT", "5432"))
DB_NAME: str = os.getenv("DB_NAME", "fams_db")
DB_USER: str = os.getenv("DB_USER", "fams_user")
DB_PASSWORD: str = os.getenv("DB_PASSWORD", "")

AI_INTERNAL_SECRET: str = os.getenv("AI_INTERNAL_SECRET", "")
JAVA_API_INTERNAL_URL: str = os.getenv("JAVA_API_INTERNAL_URL", "http://fams-api:8080")

REDIS_HOST: str = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT: int = int(os.getenv("REDIS_PORT", "6379"))
REDIS_PASSWORD: str = os.getenv("REDIS_PASSWORD", "")

AI_FACE_SIMILARITY_THRESHOLD: float = float(os.getenv("AI_FACE_SIMILARITY_THRESHOLD", "0.55"))
AI_LIVENESS_THRESHOLD: float = float(os.getenv("AI_LIVENESS_THRESHOLD", "0.6"))
AI_ENROLL_MIN_PHOTOS: int = int(os.getenv("AI_ENROLL_MIN_PHOTOS", "3"))
AI_ENROLL_MAX_PHOTOS: int = int(os.getenv("AI_ENROLL_MAX_PHOTOS", "5"))
# Slightly more lenient than AI_FACE_SIMILARITY_THRESHOLD: enrollment photos are deliberately
# taken from varied angles (that's the point — a richer embedding), so pairwise similarity
# within one enrollment batch runs lower than a same-angle verify-vs-enrolled comparison. Still
# high enough to catch "these photos are of two different people".
AI_ENROLL_SAME_PERSON_THRESHOLD: float = float(os.getenv("AI_ENROLL_SAME_PERSON_THRESHOLD", "0.45"))

STORAGE_BASE_PATH: str = os.getenv("STORAGE_BASE_PATH", "/app/storage")
