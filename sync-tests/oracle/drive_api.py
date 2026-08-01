import json
from typing import Optional

from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

from .auth import authenticate, SCOPES

FOLDER_NAME = "PicPocketTest"


class DriveApiClient:
    def __init__(self):
        creds = authenticate()
        self.service = build("drive", "v3", credentials=creds)
        self.folder_id = self._ensure_folder()

    def _ensure_folder(self) -> str:
        query = (
            f"name='{FOLDER_NAME}' and mimeType='application/vnd.google-apps.folder' "
            f"and trashed=false"
        )
        results = self.service.files().list(q=query, fields="files(id)").execute()
        files = results.get("files", [])
        if files:
            return files[0]["id"]
        metadata = {
            "name": FOLDER_NAME,
            "mimeType": "application/vnd.google-apps.folder",
        }
        folder = self.service.files().create(body=metadata, fields="id").execute()
        return folder["id"]

    def list_docs(self) -> list[dict]:
        query = f"'{self.folder_id}' in parents and mimeType='application/json' and trashed=false"
        results = (
            self.service.files()
            .list(q=query, fields="files(id, name, createdTime, modifiedTime)")
            .execute()
        )
        return results.get("files", [])

    def read_metadata(self, doc_id: str) -> Optional[dict]:
        query = (
            f"'{self.folder_id}' in parents and name='{doc_id}/metadata.json' "
            f"and trashed=false"
        )
        results = self.service.files().list(q=query, fields="files(id)").execute()
        files = results.get("files", [])
        if not files:
            return None
        file_id = files[0]["id"]
        resp = self.service.files().get_media(fileId=file_id).execute()
        return json.loads(resp.decode("utf-8"))

    def write_file(self, path: str, content: str, mime_type: str = "text/plain"):
        metadata = {
            "name": path,
            "parents": [self.folder_id],
        }
        media = MediaFileUpload(
            filename=None,
            mimetype=mime_type,
            resumable=False,
        )
        media._fd = None
        from googleapiclient.http import HttpRequest

        body = json.dumps(content) if mime_type == "application/json" else content
        import tempfile, os

        with tempfile.NamedTemporaryFile(mode="w", delete=False, suffix=".tmp") as f:
            f.write(body)
            tmp_path = f.name
        try:
            media = MediaFileUpload(tmp_path, mimetype=mime_type, resumable=False)
            self.service.files().create(
                body=metadata, media_body=media, fields="id"
            ).execute()
        finally:
            os.unlink(tmp_path)

    def write_tombstone(self, doc_id: str, device_id: str):
        path = f"{doc_id}/tombstone.json"
        tombstone = {
            "deletedBy": device_id,
            "acknowledgedBy": [],
        }
        self.write_file(path, json.dumps(tombstone), "application/json")

    def verify_file(self, doc_id: str, filename: str) -> bool:
        path = f"{doc_id}/{filename}"
        query = (
            f"'{self.folder_id}' in parents and name='{path}' "
            f"and trashed=false"
        )
        results = self.service.files().list(q=query, fields="files(id)").execute()
        return len(results.get("files", [])) > 0

    def clear_all(self):
        query = f"'{self.folder_id}' in parents and trashed=false"
        results = self.service.files().list(q=query, fields="files(id)").execute()
        for f in results.get("files", []):
            self.service.files().delete(fileId=f["id"]).execute()
