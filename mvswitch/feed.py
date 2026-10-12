"""Live game status from ESPN's public scoreboard data.

Used as a second opinion: if the mic thinks it hears a commercial, the feed
can confirm there was a stoppage (timeout, end of quarter, score, etc.), and it
tells us which other games are live, close, and late.

Note: the feed often runs 10-60 seconds behind what's on TV.
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field

log = logging.getLogger(__name__)

URL = "https://site.api.espn.com/apis/site/v2/sports/{league}/scoreboard"

LIVE, BREAK, FINAL, PREGAME, UNKNOWN = "live", "break", "final", "pregame", "unknown"

BREAK_STATUSES = {"STATUS_HALFTIME", "STATUS_END_PERIOD", "STATUS_DELAYED",
                  "STATUS_RAIN_DELAY", "STATUS_END_OF_REGULATION"}
BREAK_PLAY = re.compile(
    r"timeout|injur|challenge|review|two.minute warning|touchdown|field goal|"
    r"end of (the )?(quarter|period|half|inning)|pitching change|kickoff|safety",
    re.IGNORECASE,
)


@dataclass
class GameInfo:
    status: str = UNKNOWN
    status_name: str = ""               # raw ESPN status, e.g. STATUS_HALFTIME
    league: str = ""
    period: int = 0
    clock: float | None = None          # seconds left in the period
    scores: dict[str, int] = field(default_factory=dict)  # team abbreviation -> points
    red_zone: bool = False
    last_play: str | None = None

    def describe(self) -> str:
        if not self.scores:
            return self.status
        s = " ".join(f"{t} {p}" for t, p in self.scores.items())
        clk = ""
        if self.clock is not None:
            clk = f" {int(self.clock // 60)}:{int(self.clock % 60):02d}"
        rz = " RZ" if self.red_zone else ""
        return f"{s} P{self.period}{clk}{rz}"


def parse_event(event: dict, league: str = "") -> GameInfo:
    comp = (event.get("competitions") or [{}])[0]
    st_obj = comp.get("status") or event.get("status") or {}
    st = st_obj.get("type", {})
    state, name = st.get("state"), st.get("name", "")
    sit = comp.get("situation") or {}
    info = GameInfo(league=league, status_name=name, period=int(st_obj.get("period") or 0),
                    clock=st_obj.get("clock"), red_zone=bool(sit.get("isRedZone")),
                    last_play=(sit.get("lastPlay") or {}).get("text"))
    for c in comp.get("competitors", []):
        abbr = str(c.get("team", {}).get("abbreviation", "")).upper()
        try:
            info.scores[abbr] = int(float(c.get("score") or 0))
        except (TypeError, ValueError):
            info.scores[abbr] = 0
    if state == "post" or st.get("completed"):
        info.status = FINAL
    elif state == "pre":
        info.status = PREGAME
    elif state != "in":
        info.status = UNKNOWN
    elif name in BREAK_STATUSES:
        info.status = BREAK
    else:
        info.status = LIVE
    return info


def find_event(scoreboard: dict, team: str) -> dict | None:
    team = team.upper()
    for event in scoreboard.get("events", []):
        comp = (event.get("competitions") or [{}])[0]
        for c in comp.get("competitors", []):
            t = c.get("team", {})
            if team in (str(t.get("abbreviation", "")).upper(), str(t.get("displayName", "")).upper()):
                return event
    return None


class GameFeed:
    def __init__(self, boxes: dict[int, dict], poll_secs: float = 15,
                 break_play_secs: float = 150, fetch=None, clock=time.monotonic):
        """boxes: {box_number: {"league": "football/nfl", "team": "GB"}}"""
        self.boxes = {b: g for b, g in boxes.items() if g and g.get("team")}
        self.poll_secs = poll_secs
        self.break_play_secs = break_play_secs
        self.fetch = fetch or self._http_fetch
        self.clock = clock
        self.last_poll = -1e9
        self.status: dict[int, str] = {b: UNKNOWN for b in boxes}
        self.info: dict[int, GameInfo] = {}
        self._play: dict[int, tuple[str, float]] = {}  # box -> (text, first seen)

    @staticmethod
    def _http_fetch(league: str) -> dict:
        import requests

        r = requests.get(URL.format(league=league), timeout=8)
        r.raise_for_status()
        return r.json()

    def poll(self, force: bool = False) -> dict[int, str]:
        now = self.clock()
        if not force and now - self.last_poll < self.poll_secs:
            return self.status
        self.last_poll = now
        boards: dict[str, dict] = {}
        for box, game in self.boxes.items():
            league = game.get("league", "football/nfl")
            try:
                if league not in boards:
                    boards[league] = self.fetch(league)
                event = find_event(boards[league], game["team"])
            except Exception as e:  # network hiccup: keep last known status
                log.warning("Feed error for box %s: %s", box, e)
                continue
            if event is None:
                self.status[box] = UNKNOWN
                continue
            info = parse_event(event, league)
            if info.last_play:
                prev = self._play.get(box)
                if prev is None:
                    # Already on the board when we started: treat as old news.
                    self._play[box] = (info.last_play, now - self.break_play_secs)
                elif prev[0] != info.last_play:
                    self._play[box] = (info.last_play, now)
                text, seen = self._play[box]
                if (info.status == LIVE and BREAK_PLAY.search(text)
                        and now - seen < self.break_play_secs):
                    info.status = BREAK
            self.info[box] = info
            self.status[box] = info.status
        return self.status

    def latest_play(self, box: int) -> tuple[str, float] | None:
        """(text, time we first saw it) for the newest play in this box's game."""
        return self._play.get(box)

    def play_changed_since(self, box: int, t: float) -> bool:
        """True if a new play has shown up for this box since time t (game resumed)."""
        p = self._play.get(box)
        return bool(p and p[1] > t and not BREAK_PLAY.search(p[0]))
