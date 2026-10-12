"""Decision logic: when to switch audio and where to.

1. Commercial heard on the current game -> switch to the most exciting game
   that isn't on break, final, or pregame.
2. Game audio on the current game -> if another game (whose break should be
   over) is clearly more exciting, upgrade to it. A minimum dwell time and a
   margin keep it from ping-ponging.
"""
from __future__ import annotations

import logging
import time

from .detector import AD, GAME
from .excitement import excitement
from .feed import BREAK, FINAL, PREGAME, UNKNOWN

log = logging.getLogger(__name__)


class Controller:
    def __init__(self, navigator, boxes: list[int], feed=None, *,
                 games: dict[int, dict] | None = None,
                 primary: int | None = None, priority: list[int] | None = None,
                 settle_secs: float = 6, assumed_break_secs: float = 150,
                 require_feed_confirm: bool = False, return_to_primary: bool = True,
                 upgrade_margin: float = 0.35, min_dwell_secs: float = 45,
                 clock=time.monotonic):
        self.nav = navigator
        self.boxes = boxes
        self.feed = feed
        self.games = games or {}
        self.primary = primary or boxes[0]
        self.priority = priority or boxes
        self.settle_secs = settle_secs
        self.assumed_break_secs = assumed_break_secs
        self.require_feed_confirm = require_feed_confirm
        self.return_to_primary = return_to_primary
        self.upgrade_margin = upgrade_margin
        self.min_dwell_secs = min_dwell_secs
        self.clock = clock
        self.last_switch = -1e9
        self.break_until: dict[int, float] = {}
        self.break_started: dict[int, float] = {}

    # -- helpers -----------------------------------------------------------
    def _has_data(self) -> bool:
        return bool(self.feed and getattr(self.feed, "info", None))

    def _feed_status(self) -> dict[int, str]:
        return self.feed.poll() if self.feed else {}

    def _info(self, box):
        return getattr(self.feed, "info", {}).get(box) if self.feed else None

    def score(self, box: int) -> float:
        return excitement(self._info(box), self.games.get(box))

    def _in_break(self, box: int, now: float, feed: dict[int, str]) -> bool:
        if feed.get(box) == BREAK:
            return True
        if self.break_until.get(box, 0) <= now:
            return False
        # We assumed a break, but the feed shows new plays: it's back.
        started = self.break_started.get(box)
        if self.feed and started is not None and self.feed.play_changed_since(box, started):
            self.break_until.pop(box, None)
            return False
        return True

    def _ranked(self, now: float, feed: dict[int, str], exclude: int) -> list[int]:
        """Boxes worth switching to, best first."""
        out = []
        for box in self.priority:
            if box == exclude or box not in self.boxes:
                continue
            if feed.get(box) in (FINAL, PREGAME):
                continue
            if self._in_break(box, now, feed):
                continue
            out.append(box)
        if self._has_data():
            # stable sort keeps your priority order as the tie breaker
            out.sort(key=self.score, reverse=True)
        return out

    def _switch(self, box: int, now: float, why: str) -> bool:
        info = self._info(box)
        detail = f" [{info.describe()}, score {self.score(box)}]" if info else ""
        log.info("%s -> box %s%s", why, box, detail)
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
            targets = self._ranked(now, feed, exclude=cur)
            if targets:
                return self._switch(targets[0], now, f"Commercial on box {cur}")
            log.info("Commercial on box %s and no other game looks live; staying", cur)
            return False

        if verdict != GAME:
            return False
        self.break_until.pop(cur, None)
        if now - self.last_switch < self.min_dwell_secs:
            return False

        if self._has_data():
            # Upgrade to a clearly better game (also how it returns to your
            # main game: give that game a higher importance).
            targets = self._ranked(now, feed, exclude=cur)
            if targets:
                best = targets[0]
                if self.score(best) - self.score(cur) >= self.upgrade_margin:
                    return self._switch(best, now, f"Better game than box {cur} "
                                        f"(score {self.score(cur)})")
            return False

        # No feed: fall back to returning to the primary game after its break.
        if (self.return_to_primary and cur != self.primary
                and self.primary in self.boxes
                and not self._in_break(self.primary, now, feed)):
            return self._switch(self.primary, now, "Primary game should be back")
        return False
