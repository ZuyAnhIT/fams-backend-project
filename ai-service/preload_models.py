"""
Runs during Docker build to pre-download model weights into the image layer.
A blank image always fails face detection — that is expected; we only care that
the weights are fetched and cached before the container starts.
"""
import numpy as np
from deepface import DeepFace

img = np.zeros((100, 100, 3), dtype=np.uint8)
print("Downloading FasNet liveness weights...")
try:
    DeepFace.extract_faces(img_path=img, anti_spoofing=True, enforce_detection=False)
except Exception as e:
    print(f"Weights cached (expected on blank image): {type(e).__name__}")
print("Liveness model pre-download complete.")

print("Downloading InsightFace buffalo_l pack (SCRFD + ArcFace + landmarks, ~280MB)...")
import insightface
app = insightface.app.FaceAnalysis(name="buffalo_l", providers=["CPUExecutionProvider"])
app.prepare(ctx_id=-1, det_size=(640, 640))
print("InsightFace model pre-download complete.")
