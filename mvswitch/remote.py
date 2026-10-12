"""Remote control backends and multiview navigation.

Internal key names: LEFT, RIGHT, UP, DOWN, SELECT.
"""
from __future__ import annotations

import logging
import subprocess
import time
from collections import deque

log = logging.getLogger(__name__)

# Box numbers are reading order (left to right, top to bottom).
LAYOUTS = {
    # Two games side by side
    "2": {1: {"RIGHT": 2}, 2: {"LEFT": 1}},
    # One big game on the left, two stacked on the right
    "3": {
        1: {"RIGHT": 2},
        2: {"LEFT": 1, "DOWN": 3},
        3: {"LEFT": 1, "UP": 2},
    },
    # 2x2 grid
    "4": {
        1: {"RIGHT": 2, "DOWN": 3},
        2: {"LEFT": 1, "DOWN": 4},
        3: {"RIGHT": 4, "UP": 1},
        4: {"LEFT": 3, "UP": 2},
    },
}


def key_path(neighbors: dict, start: int, goal: int) -> list[str]:
    """Shortest list of arrow presses to move the highlight from start to goal."""
    if start == goal:
        return []
    seen = {start}
    queue = deque([(start, [])])
    while queue:
        box, path = queue.popleft()
        for key, nxt in neighbors.get(box, {}).items():
            if nxt in seen:
                continue
            if nxt == goal:
                return path + [key]
            seen.add(nxt)
            queue.append((nxt, path + [key]))
    raise ValueError(f"No path from box {start} to box {goal} in layout")


class Remote:
    def press(self, key: str) -> None:  # pragma: no cover - interface
        raise NotImplementedError


class SamsungRemote(Remote):
    """Samsung Tizen TV over the local network (YouTube TV app running on the TV)."""

    KEYS = {"LEFT": "KEY_LEFT", "RIGHT": "KEY_RIGHT", "UP": "KEY_UP",
            "DOWN": "KEY_DOWN", "SELECT": "KEY_ENTER"}

    def __init__(self, host: str, token_file: str = "samsung_token.txt"):
        from samsungtvws import SamsungTVWS

        self.tv = SamsungTVWS(host=host, port=8002, token_file=token_file,
                              name="MultiviewSwitcher", timeout=5)

    def press(self, key: str) -> None:
        self.tv.send_key(self.KEYS[key])


class RokuRemote(Remote):
    """Roku device via External Control Protocol (enable it in Roku settings)."""

    KEYS = {"LEFT": "Left", "RIGHT": "Right", "UP": "Up", "DOWN": "Down", "SELECT": "Select"}

    def __init__(self, host: str):
        self.host = host

    def press(self, key: str) -> None:
        import requests

        requests.post(f"http://{self.host}:8060/keypress/{self.KEYS[key]}", timeout=3)


class AdbRemote(Remote):
    """Google TV / Chromecast with Google TV / Fire TV via ADB over network."""

    KEYS = {"LEFT": "KEYCODE_DPAD_LEFT", "RIGHT": "KEYCODE_DPAD_RIGHT",
            "UP": "KEYCODE_DPAD_UP", "DOWN": "KEYCODE_DPAD_DOWN",
            "SELECT": "KEYCODE_DPAD_CENTER"}

    def __init__(self, host: str):
        self.serial = host if ":" in host else f"{host}:5555"
        subprocess.run(["adb", "connect", self.serial], check=False, capture_output=True)

    def press(self, key: str) -> None:
        subprocess.run(["adb", "-s", self.serial, "shell", "input", "keyevent", self.KEYS[key]],
                       check=True, capture_output=True, timeout=5)


class DryRunRemote(Remote):
    """Logs key presses instead of sending them. Good for testing detection."""

    def __init__(self):
        self.pressed: list[str] = []

    def press(self, key: str) -> None:
        self.pressed.append(key)
        log.info("[dry run] would press %s", key)


def make_remote(cfg: dict) -> Remote:
    kind = cfg.get("type", "samsung").lower()
    if kind == "samsung":
        return SamsungRemote(cfg["host"], cfg.get("token_file", "samsung_token.txt"))
    if kind == "roku":
        return RokuRemote(cfg["host"])
    if kind == "adb":
        return AdbRemote(cfg["host"])
    if kind == "dry_run":
        return DryRunRemote()
    raise ValueError(f"Unknown remote type: {kind}")


def simulate(neighbors: dict, start: int, keys: list[str]) -> int:
    """Where the highlight ends up after pressing keys (edges don't wrap)."""
    box = start
    for k in keys:
        box = neighbors.get(box, {}).get(k, box)
    return box


class Navigator:
    """Tracks which multiview box is highlighted (and therefore has audio).

    Homing: before each switch it presses UP and LEFT enough times to pin the
    highlight in the top-left box, then navigates from there. That way it can
    never lose track, even if someone used the real remote.
    """

    def __init__(self, remote: Remote, neighbors: dict, start_box: int = 1,
                 key_delay: float = 0.35, wake_key: str | None = None,
                 confirm_key: str | None = None, home: bool = True,
                 home_keys: tuple[str, ...] = ("UP", "LEFT"), home_presses: int = 2,
                 sleep=time.sleep):
        self.remote = remote
        self.neighbors = neighbors
        self.current = start_box
        self.key_delay = key_delay
        self.wake_key = wake_key
        self.confirm_key = confirm_key
        self.sleep = sleep
        self.home_seq = [k for k in home_keys for _ in range(home_presses)] if home else []
        self.home_box = None
        if self.home_seq:
            ends = {simulate(neighbors, b, self.home_seq) for b in neighbors}
            if len(ends) != 1:
                raise ValueError("Homing keys don't land on one box from every box; "
                                 "raise home_presses or set home: false")
            self.home_box = ends.pop()

    def _press_all(self, keys: list[str]) -> None:
        for key in keys:
            self.remote.press(key)
            self.sleep(self.key_delay)

    def resync(self) -> None:
        """Pin the highlight to the home box so we know where it is."""
        if not self.home_seq:
            return
        keys = ([self.wake_key] if self.wake_key else []) + self.home_seq
        self._press_all(keys)
        self.current = self.home_box
        log.info("Highlight reset to box %s", self.home_box)

    def go_to(self, box: int) -> None:
        if box == self.current:
            return
        if self.home_seq:
            keys = self.home_seq + key_path(self.neighbors, self.home_box, box)
        else:
            keys = key_path(self.neighbors, self.current, box)
        if self.wake_key:
            keys = [self.wake_key] + keys
        if self.confirm_key:
            keys = keys + [self.confirm_key]
        log.info("Switching audio: box %s -> box %s (%s)", self.current, box, " ".join(keys))
        self._press_all(keys)
        self.current = box
