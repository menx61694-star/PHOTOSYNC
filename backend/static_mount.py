from pathlib import Path

from fastapi.staticfiles import StaticFiles

WEB_DIR = Path(__file__).resolve().parent.parent / "web"


def mount_dashboard(app):
    """Mount the PHOTOSYNC web dashboard at /dashboard."""
    app.mount("/dashboard", StaticFiles(directory=WEB_DIR, html=True), name="dashboard")
