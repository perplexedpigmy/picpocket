import logging
from pathlib import Path

import pytest

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from infra import nextcloud
from oracle.nextcloud_oracle import NextcloudOracle

logging.basicConfig(
    level=logging.INFO, format="%(levelname)s %(message)s"
)
logger = logging.getLogger(__name__)


@pytest.fixture(scope="session", autouse=True)
def infra():
    nextcloud.start()
    yield
    nextcloud.stop()


@pytest.fixture
def fuse_path() -> Path:
    return nextcloud.RCLONE_MOUNT_POINT


@pytest.fixture
def nc_container() -> str:
    return nextcloud.NEXTCLOUD_CONTAINER


@pytest.fixture
def ocr() -> NextcloudOracle:
    return NextcloudOracle()
