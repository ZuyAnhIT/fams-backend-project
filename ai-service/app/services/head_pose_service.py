from __future__ import annotations

import logging

import numpy as np

logger = logging.getLogger(__name__)

# InsightFace's 106-point landmark scheme (landmark_2d_106) — indices for the two eye contours,
# verified empirically against this service's own test fixture (not blindly trusted from a
# secondhand diagram): each eye is a contiguous run of 10 points, clustered by relative position
# to the face bbox. See docs/api/face-id-management-api.md for how these were derived.
_LEFT_EYE_IDX = slice(33, 43)
_RIGHT_EYE_IDX = slice(87, 97)

# Degrees. A frontal ("center") face should read close to 0 on both axes; a genuine turn/tilt
# needs to clear this margin to count — wide enough to tolerate normal hand-held-phone jitter,
# narrow enough that a static photo held at a fixed angle can't trivially claim "center".
YAW_THRESHOLD_DEG = 15.0
PITCH_THRESHOLD_DEG = 12.0
CENTER_MAX_DEVIATION_DEG = 10.0

# Generous sanity bound for the BASELINE itself (not the tight tolerance above, which is for
# classifying frames relative to that baseline). Even though InsightFace's pose estimate (3D
# similarity transform against a canonical mean face, not a generic 6-point solvePnP — see
# docs/api/face-id-management-api.md) is far more accurate than the old dlib pipeline, a real
# phone selfie camera is still essentially never held perfectly at eye level, so some natural
# bias in the 'center' frame's own reading is expected and fine. This bound only rejects
# genuinely implausible baselines (e.g. a profile shot).
CENTER_BASELINE_SANITY_DEG = 45.0

# Eye "openness" = height/width of the 10-point eye-contour cluster's bounding extent — the same
# principle as the classic Eye Aspect Ratio (Soukupová & Čech, 2016: shrinks sharply when the
# eye closes) computed via extents rather than matched corner/lid point PAIRS, because
# InsightFace's exact within-cluster point ORDER (which of the 10 points is "upper lid center"
# vs "lower lid center") is not documented precisely enough to trust blind pairing — extents are
# robust to that ambiguity. Calibrated against this service's own open-eye test fixture
# (~0.25-0.26); NOT yet validated against a real closed-eye photo (this environment has no
# camera) — flag for real-device QA, same caveat as the previous EAR threshold.
EYE_OPEN_THRESHOLD = 0.16


def estimate_head_pose(face) -> tuple[float, float, float] | None:
    """Returns (pitch, yaw, roll) in degrees from an InsightFace Face object, or None if this
    face has no pose (only present when the landmark_3d_68 model ran, which buffalo_l always
    includes). pitch > 0 ~ looking up, pitch < 0 ~ looking down; yaw > 0 / < 0 ~ turned to one
    side vs the other — same sign convention as before, InsightFace's own pose[0]=pitch,
    pose[1]=yaw, pose[2]=roll (see insightface/model_zoo/landmark.py)."""
    pose = getattr(face, "pose", None)
    if pose is None:
        return None
    pitch, yaw, roll = (float(v) for v in pose)
    return pitch, yaw, roll


def estimate_baseline_pose(face) -> tuple[float, float] | None:
    """Pitch/yaw to use as the zero-point for delta-based classification of the rest of a
    challenge's frames — derived from that challenge's own 'center' frame instead of assuming
    an idealized, unrealistic (0, 0) frontal pose. Returns None (caller falls back to an
    absolute (0, 0) baseline) if pose can't be read or is wildly implausible as a "roughly facing
    the camera" starting point."""
    pose = estimate_head_pose(face)
    if pose is None:
        return None
    pitch, yaw, _roll = pose
    if abs(pitch) > CENTER_BASELINE_SANITY_DEG or abs(yaw) > CENTER_BASELINE_SANITY_DEG:
        return None
    return pitch, yaw


def eye_openness(eye_points: np.ndarray) -> float:
    """height/width of a 10-point eye-contour cluster's bounding box — see EYE_OPEN_THRESHOLD
    docstring for why this shape (not classic paired-point EAR) was used."""
    xs = eye_points[:, 0]
    ys = eye_points[:, 1]
    width = float(xs.max() - xs.min())
    height = float(ys.max() - ys.min())
    if width == 0:
        return 0.0
    return height / width


def classify_frame(face, baseline_pitch: float = 0.0, baseline_yaw: float = 0.0) -> set[str]:
    """Returns every action this single frame's face pose/eye-state satisfies (a frame can
    satisfy multiple loosely — e.g. 'center' and nothing else, or 'blink' regardless of yaw).
    The caller matches this against the ONE expected action for that position in the sequence.

    `face` is an InsightFace Face object (from face_service.detect_single_face) — carries both
    pose and landmark_2d_106 in one shot, unlike the old dlib pipeline which needed the raw image
    array passed separately for pose.

    Pose classification is relative to (baseline_pitch, baseline_yaw) — the challenge's own
    'center' frame's measured pose (see estimate_baseline_pose), not an assumed (0, 0)."""
    satisfied: set[str] = set()

    pose = estimate_head_pose(face)
    if pose is not None:
        pitch, yaw, roll = pose
        pitch_delta = pitch - baseline_pitch
        yaw_delta = yaw - baseline_yaw
        if abs(yaw_delta) <= CENTER_MAX_DEVIATION_DEG and abs(pitch_delta) <= CENTER_MAX_DEVIATION_DEG:
            satisfied.add("center")
        if yaw_delta >= YAW_THRESHOLD_DEG:
            satisfied.add("turn_right")
        elif yaw_delta <= -YAW_THRESHOLD_DEG:
            satisfied.add("turn_left")
        if pitch_delta >= PITCH_THRESHOLD_DEG:
            satisfied.add("look_up")
        elif pitch_delta <= -PITCH_THRESHOLD_DEG:
            satisfied.add("look_down")

    landmarks = getattr(face, "landmark_2d_106", None)
    if landmarks is not None:
        left_openness = eye_openness(landmarks[_LEFT_EYE_IDX])
        right_openness = eye_openness(landmarks[_RIGHT_EYE_IDX])
        if (left_openness + right_openness) / 2.0 < EYE_OPEN_THRESHOLD:
            satisfied.add("blink")

    return satisfied
