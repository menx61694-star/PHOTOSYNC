# PHOTOSYNC Web Dashboard

The dashboard connects to the backend WebSocket at `/ws` and displays each `photo_uploaded` event instantly without a page refresh.

For local testing, serve this directory from the same origin/reverse proxy as the FastAPI backend so `/ws` resolves correctly.
