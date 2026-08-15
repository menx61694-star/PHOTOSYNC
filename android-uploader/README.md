# PHOTOSYNC Android Uploader

Minimal Android uploader contract:

1. Pick an image from the device gallery.
2. Build a `multipart/form-data` request with field name `file`.
3. POST it to the PHOTOSYNC backend `/upload` endpoint.
4. The backend saves the image and broadcasts `photo_uploaded` over WebSocket.

Example request:

```text
POST /upload
Content-Type: multipart/form-data
file=<image bytes>
```

The Android client should keep the backend URL configurable rather than hard-coding a public server address.

Security note: do not expose the current prototype directly to the public internet. Add authentication and HTTPS before doing so.
