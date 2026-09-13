import logging
import subprocess
import time
from typing import Optional

logger = logging.getLogger(__name__)


class AdbDevice:
    def __init__(self, serial: str):
        self.serial = serial

    def exec(self, *args: str, timeout: int = 30) -> str:
        cmd = ["adb", "-s", self.serial, *args]
        logger.debug("Running: %s", " ".join(cmd))
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.returncode != 0:
            raise RuntimeError(
                f"adb command failed: {' '.join(cmd)}\n{result.stderr.strip()}"
            )
        return result.stdout.strip()

    def shell(self, command: str, timeout: int = 30) -> str:
        return self.exec("shell", command, timeout=timeout)

    def logcat(self, clear: bool = False) -> str:
        args = ["logcat", "-d"]
        if clear:
            args.append("-c")
        return self.exec(*args, timeout=10)

    def logcat_filtered(self, tag: str, clear: bool = False) -> str:
        """Read only one tag from the logcat buffer.

        `adb logcat -d -s <tag>` is much cheaper than dumping the whole buffer
        every second, which the sync watchers poll repeatedly.
        """
        args = ["logcat", "-d", "-s", tag]
        if clear:
            args.insert(1, "-c")
        return self.exec(*args, timeout=10)

    def logcat_clear(self):
        self.exec("logcat", "-c", "-b", "all", timeout=5)

    def install_apk(self, apk_path: str):
        self.exec("install", "-r", apk_path, timeout=120)
        out = self.shell(f"pm list packages | grep com.picpocket.app")
        if "com.picpocket.app" not in out:
            raise RuntimeError("APK installation verification failed")

    def push(self, local: str, remote: str):
        self.exec("push", local, remote, timeout=30)

    def is_installed(self) -> bool:
        out = self.shell("pm list packages | grep com.picpocket.app || true")
        return "com.picpocket.app" in out

    def clear_app_data(self):
        self.shell(f"pm clear com.picpocket.app")

    @staticmethod
    def discover_devices() -> list[str]:
        result = subprocess.run(
            ["adb", "devices"], capture_output=True, text=True, timeout=10
        )
        lines = result.stdout.strip().splitlines()[1:]
        serials = []
        for line in lines:
            if "\tdevice" in line:
                serials.append(line.split("\t")[0])
        emulators = [s for s in serials if s.startswith("emulator-")]
        return emulators if emulators else serials

    @staticmethod
    def wait_for_boot(serial: str, timeout: int = 120) -> "AdbDevice":
        device = AdbDevice(serial)
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                boot = device.shell("getprop sys.boot_completed").strip()
                if boot == "1":
                    return device
            except RuntimeError:
                pass
            time.sleep(3)
        raise TimeoutError(f"Device {serial} did not boot within {timeout}s")
