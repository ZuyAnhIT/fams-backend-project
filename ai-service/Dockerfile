FROM python:3.11-slim

# Runtime + dlib build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    libopenblas-dev \
    liblapack-dev \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libgomp1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# ── Heavy ML frameworks in isolated layers so Docker caches them independently.
# ── If a download breaks, only that layer retries; earlier layers stay cached.

# Layer A: PyTorch CPU (~200 MB) — needed for FasNet liveness model
RUN pip install --no-cache-dir \
    torch \
    --extra-index-url https://download.pytorch.org/whl/cpu

# Layer B: TensorFlow (~570 MB) — required by deepface at import time
RUN pip install --no-cache-dir tensorflow

# ── Application dependencies ──────────────────────────────────────────────────
WORKDIR /app

COPY requirements.txt .
# deepface finds tensorflow + torch already satisfied → no re-download of heavy deps
RUN pip install --no-cache-dir -r requirements.txt

# Force CPU-only execution for both frameworks at runtime
ENV TF_CPP_MIN_LOG_LEVEL=3
ENV CUDA_VISIBLE_DEVICES=-1

# TF 2.15+ with Keras 3.x requires the tf-keras compatibility shim.
RUN pip install --no-cache-dir tf-keras

# opencv-python (pulled by deepface) needs libGL at runtime.
# Installed as a separate layer AFTER pip installs to preserve torch/tensorflow/dlib cache.
RUN apt-get update && apt-get install -y --no-install-recommends libgl1 && rm -rf /var/lib/apt/lists/*

# Pre-bake deepface FasNet liveness model weights into the image layer
COPY preload_models.py /tmp/preload_models.py
RUN python /tmp/preload_models.py

# Verify InsightFace (SCRFD + ArcFace + landmarks) models are accessible
RUN python -c "import insightface; insightface.app.FaceAnalysis(name='buffalo_l', providers=['CPUExecutionProvider']).prepare(ctx_id=-1, det_size=(640, 640)); print('insightface models OK')"

COPY . .

RUN mkdir -p /app/storage/enrollments /app/storage/checkins

EXPOSE 5000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5000", "--log-level", "info"]
