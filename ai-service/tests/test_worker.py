from __future__ import annotations

import unittest
from unittest.mock import MagicMock, patch

from app import worker


class FaceVerifyWorkerTest(unittest.TestCase):
    def test_challenge_job_reports_liveness_as_verified(self) -> None:
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
            patch.object(worker, "_load_challenge_frame", return_value=b"jpeg"),
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
