from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from app import worker


class FaceVerifyWorkerTest(unittest.TestCase):
    def test_challenge_job_reuses_preverified_embedding(self) -> None:
        """Challenge flow: worker must use the averaged embedding the challenge already stored,
        NOT re-run extract_embedding (which would cost another ~10-15s cold and be strictly
        worse — single-frame vs the challenge's 3-frame average)."""
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = ([1.0, 0.0],)

        job = {
            "source_id": "checkin-id",
            "source_type": "checkin",
            "tenant_id": "tenant-id",
            "employee_id": "employee-id",
            "challenge_id": "challenge-id",
            "requires_liveness": False,
        }

        with (
            patch.object(
                worker, "_load_challenge_frame_and_embedding",
                return_value=(b"jpeg", [1.0, 0.0]),
            ),
            patch.object(worker, "save_checkin_photo"),
            patch.object(worker, "get_conn", return_value=connection),
            patch.object(worker, "put_conn"),
            patch.object(worker, "extract_embedding") as extract_embedding,
            patch.object(worker, "cosine_similarity", return_value=0.88),
            patch.object(worker, "send_face_result") as send_face_result,
        ):
            worker._process_job(job)

        extract_embedding.assert_not_called()
        send_face_result.assert_called_once_with(
            "checkin-id",
            "tenant-id",
            "checkin",
            True,
            True,
            0.88,
            None,
        )

    def test_plain_photo_without_liveness_keeps_liveness_unresolved(self) -> None:
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = ([1.0, 0.0],)

        job = {
            "source_id": "checkin-id",
            "source_type": "checkin",
            "tenant_id": "tenant-id",
            "employee_id": "employee-id",
            "face_image_base64": "anBlZw==",
            "requires_liveness": False,
        }

        with (
            patch.object(worker, "save_checkin_photo"),
            patch.object(worker, "get_conn", return_value=connection),
            patch.object(worker, "put_conn"),
            patch.object(worker, "extract_embedding", return_value=[1.0, 0.0]),
            patch.object(worker, "cosine_similarity", return_value=0.88),
            patch.object(worker, "send_face_result") as send_face_result,
        ):
            worker._process_job(job)

        send_face_result.assert_called_once_with(
            "checkin-id",
            "tenant-id",
            "checkin",
            True,
            None,
            0.88,
            None,
        )


if __name__ == "__main__":
    unittest.main()
