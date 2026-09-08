"""
Pre-downloads all model weights into the image at build time.
Runs during 'docker build' only — never at runtime.
"""
import numpy as np

# ── Step 1: trigger deepface to download MiniFASNet weights ───────────────────
print("Pre-baking deepface MiniFASNet liveness models...")
from deepface import DeepFace

_blank = np.zeros((100, 100, 3), dtype=np.uint8)
try:
    DeepFace.extract_faces(img_path=_blank, anti_spoofing=True, enforce_detection=False)
except Exception as e:
    print(f"  Expected failure on blank image: {type(e).__name__}")

print("  deepface MiniFASNet models ready")

# ── Step 2: pre-bake InsightFace buffalo_l ────────────────────────────────────
print("Pre-baking InsightFace buffalo_l (SCRFD + ArcFace + landmarks)...")
import insightface

_iface = insightface.app.FaceAnalysis(name="buffalo_l", providers=["CPUExecutionProvider"])
_iface.prepare(ctx_id=-1, det_size=(640, 640))
print("  buffalo_l ready")

print("\nAll model weights pre-baked successfully.")
