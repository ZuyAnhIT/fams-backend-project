FROM python:3.11-slim

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
    libgl1 \
    && rm -rf /var/lib/apt/lists/*

# PyTorch CPU — required by deepface's MiniFASNet anti-spoofing models
RUN pip install --no-cache-dir \
    torch \
    --extra-index-url https://download.pytorch.org/whl/cpu

# TensorFlow — required by deepface at import time
RUN pip install --no-cache-dir tensorflow tf-keras

# deepface — face anti-spoofing (MiniFASNetV2 + MiniFASNetV1SE)
RUN pip install --no-cache-dir deepface==0.0.93

WORKDIR /app
ENV HOME=/app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download all model weights so they are baked into the image.
# The container never needs outbound internet access at runtime.
COPY preload_models.py /tmp/preload_models.py
RUN python /tmp/preload_models.py

COPY . .

RUN mkdir -p /app/storage/enrollments /app/storage/checkins

RUN groupadd --system --gid 1001 fams && \
    useradd --system --uid 1001 --gid 1001 --home-dir /app --no-create-home fams && \
    chown -R fams:fams /app

USER fams

EXPOSE 5000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5000", "--log-level", "info"]
