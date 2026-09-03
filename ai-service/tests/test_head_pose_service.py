from __future__ import annotations

import unittest
from types import SimpleNamespace

import numpy as np

from app.services.head_pose_service import (
    YAW_THRESHOLD_DEG,
    PITCH_THRESHOLD_DEG,
    classify_frame,
)


def _fake_face(pitch: float = 0.0, yaw: float = 0.0, roll: float = 0.0):
    """Minimal stand-in for an InsightFace Face: only .pose and .landmark_2d_106 are read by
    classify_frame. Landmarks are left as zeros (eye-openness ~0) — the blink flag that produces
    is irrelevant to the pose-direction assertions below."""
    return SimpleNamespace(pose=(pitch, yaw, roll), landmark_2d_106=np.zeros((106, 2), dtype=float))


class HeadPoseDirectionTest(unittest.TestCase):
    """Regression guard for the 2026-09-03 real-device QA fix: the liveness instruction
    "quay đầu sang phải/trái" means the user's OWN right/left, and selfie frames are captured
    un-mirrored, so InsightFace reports negative yaw for an own-right turn and positive yaw for
    an own-left turn."""

    def test_own_right_turn_is_classified_turn_right(self) -> None:
        satisfied = classify_frame(_fake_face(yaw=-(YAW_THRESHOLD_DEG + 5)))
        self.assertIn("turn_right", satisfied)
        self.assertNotIn("turn_left", satisfied)

    def test_own_left_turn_is_classified_turn_left(self) -> None:
        satisfied = classify_frame(_fake_face(yaw=YAW_THRESHOLD_DEG + 5))
        self.assertIn("turn_left", satisfied)
        self.assertNotIn("turn_right", satisfied)

    def test_frontal_face_is_center(self) -> None:
        self.assertIn("center", classify_frame(_fake_face()))

    def test_baseline_offset_is_respected(self) -> None:
        # Camera held slightly off-axis: baseline yaw = -20. A frame at the same -20 is "center",
        # not a turn.
        satisfied = classify_frame(_fake_face(yaw=-20.0), baseline_yaw=-20.0)
        self.assertIn("center", satisfied)
        self.assertNotIn("turn_right", satisfied)

    def test_pitch_directions_unchanged(self) -> None:
        self.assertIn("look_up", classify_frame(_fake_face(pitch=PITCH_THRESHOLD_DEG + 5)))
        self.assertIn("look_down", classify_frame(_fake_face(pitch=-(PITCH_THRESHOLD_DEG + 5))))


if __name__ == "__main__":
    unittest.main()
