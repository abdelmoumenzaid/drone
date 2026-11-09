FROM python:3.12-slim

# ffmpeg pour le retour caméra FPV
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .

# HTTP (page + WS + vidéo). Le reste (UDP 8000/7078) passe par --network host.
EXPOSE 8080

CMD ["python3", "bridge.py"]
