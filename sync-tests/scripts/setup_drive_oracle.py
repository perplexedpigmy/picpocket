#!/usr/bin/env python3
"""
One-time OAuth setup for the Drive API oracle.

Idempotent — exits early if refresh_token.json already exists.
Creates the PicPocketTest folder on first run.
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from oracle.auth import authenticate, TOKEN_FILE, CLIENT_SECRET_FILE


def main():
    if TOKEN_FILE.exists():
        print("Already configured (refresh_token.json exists)")
        return

    if not CLIENT_SECRET_FILE.exists():
        print(
            f"Error: missing {CLIENT_SECRET_FILE}\n\n"
            "Download client_secret.json from GCP Console:\n"
            "  https://console.cloud.google.com/apis/credentials\n"
            "Select your OAuth 2.0 Desktop client, download JSON,\n"
            f"and save it to:\n  {CLIENT_SECRET_FILE}",
            file=sys.stderr,
        )
        sys.exit(1)

    print("Opening browser for OAuth consent...")
    creds = authenticate()

    from oracle.drive_api import FOLDER_NAME
    from googleapiclient.discovery import build

    service = build("drive", "v3", credentials=creds)
    query = (
        f"name='{FOLDER_NAME}' and mimeType='application/vnd.google-apps.folder' "
        f"and trashed=false"
    )
    results = service.files().list(q=query, fields="files(id, name)").execute()
    files = results.get("files", [])
    if files:
        print(f"Folder '{FOLDER_NAME}' already exists (id: {files[0]['id']})")
    else:
        folder = (
            service.files()
            .create(
                body={"name": FOLDER_NAME, "mimeType": "application/vnd.google-apps.folder"},
                fields="id",
            )
            .execute()
        )
        print(f"Created folder '{FOLDER_NAME}' (id: {folder['id']})")

    print(f"\nSetup complete! Token saved to {TOKEN_FILE}")


if __name__ == "__main__":
    main()
