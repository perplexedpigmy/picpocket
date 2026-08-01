import json
import os
from pathlib import Path

from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request
from google_auth_oauthlib.flow import InstalledAppFlow

SECRETS_DIR = Path(__file__).resolve().parent.parent / "secrets"
CLIENT_SECRET_FILE = SECRETS_DIR / "client_secret.json"
TOKEN_FILE = SECRETS_DIR / "refresh_token.json"
SCOPES = ["https://www.googleapis.com/auth/drive.file"]


def load_or_refresh_token() -> Credentials:
    token_path = SECRETS_DIR / "refresh_token.json"
    creds = None
    if token_path.exists():
        with open(token_path) as f:
            creds = Credentials.from_authorized_user_info(json.load(f), SCOPES)
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(Request())
        _save_token(creds)
        return creds
    return creds


def authenticate() -> Credentials:
    creds = load_or_refresh_token()
    if creds and creds.valid:
        return creds
    if not CLIENT_SECRET_FILE.exists():
        raise FileNotFoundError(
            f"Missing {CLIENT_SECRET_FILE}. "
            "Download it from https://console.cloud.google.com/apis/credentials "
            "and save it to sync-tests/secrets/client_secret.json"
        )
    flow = InstalledAppFlow.from_client_secrets_file(str(CLIENT_SECRET_FILE), SCOPES)
    creds = flow.run_local_server(port=0, open_browser=True)
    _save_token(creds)
    return creds


def _save_token(creds: Credentials) -> None:
    token = {
        "token": creds.token,
        "refresh_token": creds.refresh_token,
        "token_uri": creds.token_uri,
        "client_id": creds.client_id,
        "client_secret": creds.client_secret,
        "scopes": creds.scopes,
    }
    with open(TOKEN_FILE, "w") as f:
        json.dump(token, f)
    os.chmod(TOKEN_FILE, 0o600)
