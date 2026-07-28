from __future__ import annotations

import logging

import cv2
import numpy as np

logger = logging.getLogger(__name__)

# Generic 3D reference face (arbitrary units) — the standard 6-point model used for
# solvePnP-based head pose estimation (nose tip is the coordinate-system origin).
_MODEL_POINTS = np.array([
    (0.0, 0.0, 0.0),          # Nose tip
    (0.0, -330.0, -65.0),     # Chin
    (-225.0, 170.0, -135.0),  # Left eye, outer corner
    (225.0, 170.0, -135.0),   # Right eye, outer corner
    (-150.0, -150.0, -125.0), # Left mouth corner
    (150.0, -150.0, -125.0),  # Right mouth corner
], dtype=np.float64)

# Degrees. A frontal ("center") face should read close to 0 on both axes; a genuine turn/tilt
# needs to clear this margin to count — wide enough to tolerate normal hand-held-phone jitter,
# narrow enough that a static photo held at a fixed angle can't trivially claim "center".
YAW_THRESHOLD_DEG = 15.0
PITCH_THRESHOLD_DEG = 12.0
CENTER_MAX_DEVIATION_DEG = 10.0

# Eye Aspect Ratio: open eyes are usually 0.25-0.35 for a face-forward camera; this drops
# sharply (typically <0.15-0.18) when eyes are closed. See Soukupová & Čech, "Real-Time Eye
# Blink Detection using Facial Landmarks" (2016) — the standard reference for this technique.
EAR_BLINK_THRESHOLD = 0.20


def _landmark_points(landmarks: dict) -> np.ndarray | None:
    """Extract the 6 points solvePnP needs, in the same order as _MODEL_POINTS."""
    try:
        nose_tip = landmarks["nose_tip"][2]
        chin = landmarks["chin"][8]
        left_eye_outer = landmarks["left_eye"][0]
        right_eye_outer = landmarks["right_eye"][3]
        mouth_left = landmarks["top_lip"][0]
        mouth_right = landmarks["top_lip"][6]
    except (KeyError, IndexError):
        return None
    return np.array([nose_tip, chin, left_eye_outer, right_eye_outer, mouth_left, mouth_right],
                     dtype=np.float64)


def estimate_head_pose(landmarks: dict, image_shape: tuple[int, int]) -> tuple[float, float, float] | None:
    """Returns (pitch, yaw, roll) in degrees, or None if landmarks are unusable.

    pitch > 0 ~ looking up, pitch < 0 ~ looking down (sign depends on solvePnP's convention,
    calibrated against a known-frontal frame during testing — see docs/api/face-id-management-api.md).
    yaw > 0 / < 0 ~ turned to one side vs the other.
    """
    image_points = _landmark_points(landmarks)
    if image_points is None:
        return None

    height, width = image_shape[:2]
    focal_length = width
    center = (width / 2, height / 2)
    camera_matrix = np.array([
        [focal_length, 0, center[0]],
        [0, focal_length, center[1]],
        [0, 0, 1],
    ], dtype=np.float64)
    dist_coeffs = np.zeros((4, 1))

    success, rotation_vector, translation_vector = cv2.solvePnP(
        _MODEL_POINTS, image_points, camera_matrix, dist_coeffs, flags=cv2.SOLVEPNP_ITERATIVE)
    if not success:
        return None

    rotation_matrix, _ = cv2.Rodrigues(rotation_vector)
    projection_matrix = np.hstack((rotation_matrix, translation_vector))
    euler_angles = cv2.decomposeProjectionMatrix(projection_matrix)[6]
    pitch, yaw, roll = (float(a) for a in euler_angles.flatten())

    # cv2.decomposeProjectionMatrix has a well-known sign ambiguity for a face looking roughly
    # at the camera: it can return the mathematically-equivalent "flipped" solution (pitch/roll
    # near +-180 instead of near 0). Detected via roll being near +-180 (a webcam-facing head is
    # never actually rolled that far) and corrected by folding pitch/roll back into [-90, 90].
    if abs(roll) > 90:
        pitch = pitch - 180 if pitch > 0 else pitch + 180
        roll = roll - 180 if roll > 0 else roll + 180

    return pitch, yaw, roll


def eye_aspect_ratio(eye_points: list[tuple[int, int]]) -> float:
    """Standard 6-point EAR: (|p2-p6| + |p3-p5|) / (2 * |p1-p4|)."""
    p = [np.array(pt, dtype=np.float64) for pt in eye_points]
    vertical_1 = np.linalg.norm(p[1] - p[5])
    vertical_2 = np.linalg.norm(p[2] - p[4])
    horizontal = np.linalg.norm(p[0] - p[3])
    if horizontal == 0:
        return 0.0
    return (vertical_1 + vertical_2) / (2.0 * horizontal)


def classify_frame(landmarks: dict, image_shape: tuple[int, int]) -> set[str]:
    """Returns every action this single frame's face pose/eye-state satisfies (a frame can
    satisfy multiple loosely — e.g. 'center' and nothing else, or 'blink' regardless of yaw).
    The caller matches this against the ONE expected action for that position in the sequence."""
    satisfied: set[str] = set()

    pose = estimate_head_pose(landmarks, image_shape)
    if pose is not None:
        pitch, yaw, roll = pose
        if abs(yaw) <= CENTER_MAX_DEVIATION_DEG and abs(pitch) <= CENTER_MAX_DEVIATION_DEG:
            satisfied.add("center")
        if yaw >= YAW_THRESHOLD_DEG:
            satisfied.add("turn_right")
        elif yaw <= -YAW_THRESHOLD_DEG:
            satisfied.add("turn_left")
        if pitch >= PITCH_THRESHOLD_DEG:
            satisfied.add("look_up")
        elif pitch <= -PITCH_THRESHOLD_DEG:
            satisfied.add("look_down")

    left_ear = eye_aspect_ratio(landmarks["left_eye"])
    right_ear = eye_aspect_ratio(landmarks["right_eye"])
    if (left_ear + right_ear) / 2.0 < EAR_BLINK_THRESHOLD:
        satisfied.add("blink")

    return satisfied
