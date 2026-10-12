"""Decision logic: when to switch audio and where to."""
from __future__ import annotations

import logging
import time

from .detector import AD, GAME
from .feed import BREAK, FINAL, PREGAME, UNKNOWN

log = logging.getLogger(__name__)


class Controller:
    def __init__(self, navigator, boxes: list[int], feed=None, *,
                 primary: int | None = None, priority: list[int] | None = None,
                 settle_secs: float = 6, assumed_break_secs: float = 150,
                 require_feed_confirm: bool = False, return_to_primary: bool = True,
                 clock=time.monotonic):
        self.nav = navigator
        self.boxes = boxes
        self.feed = feed
        self.primary = primary or boxes[0]
        self.priority = priority or boxes
        self.settle_secs = settle_secs
        self.assumed_break_secs = assumed_break_secs
        self.require_feed_confirm = require_feed_confirm
        self.return_to_primary = return_to_primary
        self.clock = clock
        self.last_switch = -1e9
        self.break_until: dict[int, float] = {}
        self.break_started: dict[int, float] = {}

    # -- helpers -----------------------------------------------------------
    def _feed_status(self) -> dict[int, str]:
        if not self.feed:
            return {}
        return self.feed.poll()

    def _in_break(self, box: int, now: float, feed: dict[int, str]) -> bool:
        if feed.get(box) == BREAK:
            return True
        return self.break_until.get(box, 0) > now

    def _candidates(self, now: float, feed: dict[int, str], exclude: int) -> list[int]:
        out = []
        for box in self.priority:
            if box == exclude or box not in self.boxes:
                continue
            if feed.get(box) in (FINAL, PREGAME):
                continue
            if self._in_break(box, now, feed):
                continue
            out.append(box)
        return out

    def _switch(self, box: int, now: float) -> bool:
        self.nav.go_to(box)
        self.last_switch = now
        return True

    # -- main step ----------------------------------------------------------
    def step(self, verdict: str) -> bool:
        """Feed in the current audio verdict. Returns True if we switched (caller
        should reset the detector so it re-listens to the new game)."""
        now = self.clock()
        if now - self.last_switch < self.settle_secs:
            return False
        feed = self._feed_status()
        cur = self.nav.current

        if verdict == AD:
            if self.require_feed_confirm and feed.get(cur, UNKNOWN) not in (BREAK, UNKNOWN):
                log.info("Mic says commercial on box %s but feed says live; staying", cur)
                return False
            if self.break_until.get(cur, 0) <= now:
                self.break_started[cur] = now
            self.break_until[cur] = now + self.assumed_break_secs
            targets = self._candidates(now, feed, exclude=cur)
            if targets:
                log.info("Commercial on box %s -> trying box %s", cur, targets[0])
                return self._switch(targets[0], now)
            log.info("Commercial on box %s and no other game looks live; staying", cur)
            return False

        if verdict == GAME:
            self.break_until.pop(cur, None)
            if (self.return_to_primary and cur != self.primary
                    and self.primary in self.boxes
                    and feed.get(self.primary) not in (FINAL, PREGAME)):
                started = self.break_started.get(self.primary, now)
                resumed = (self.feed is not None
                           and self.feed.play_changed_since(self.primary, started))
                if resumed or not self._in_break(self.primary, now, feed):
                    log.info("Primary game (box %s) should be back; returning", self.primary)
                    self.break_until.pop(self.primary, None)
                    return self._switch(self.primary, now)
        return False
