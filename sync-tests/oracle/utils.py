import logging
from urllib.parse import urlparse

import requests

logger = logging.getLogger(__name__)


def put_collection(url: str, auth, timeout: int = 10):
    parts = urlparse(url).path.strip("/").split("/")
    try:
        start = parts.index("PicPocketTest") + 1
    except ValueError:
        start = 0
    end = len(parts)
    if "." in parts[-1]:
        end -= 1
    base = f"{urlparse(url).scheme}://{urlparse(url).netloc}"
    for i in range(start, end):
        parent = base + "/" + "/".join(parts[:i+1])
        resp = requests.request("MKCOL", parent, auth=auth, timeout=timeout)
        if resp.status_code not in (201, 405):
            resp.raise_for_status()
