"""Live game status from ESPN's public scoreboard data.

Used as a second opinion: if the mic thinks it hears a commercial, the feed
can confirm there was a stoppage (timeout, end of quarter, score, etc.), and it
tells us which other games are live and worth switching to.

Note: the feed often runs 10-60 seconds behind what's on TV.
"""
from __future__ import annotations

import logging
import re
import time

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


def parse_event(event: dict) -> tuple[str, str | None]:
    """Return (status, last_play_text) for one ESPN event."""
    comp = (event.get("competitions") or [{}])[0]
    st = (comp.get("status") or event.get("status") or {}).get("type", {})
    state, name = st.get("state"), st.get("name", "")
    last_play = ((comp.get("situation") or {}).get("lastPlay") or {}).get("text")
    if state == "post" or st.get("completed"):
        return FINAL, last_play
    if state == "pre":
        return PREGAME, last_play
    if state != "in":
        return UNKNOWN, last_play
    if name in BREAK_STATUSES:
        return BREAK, last_play
    return LIVE, last_play


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
            status, play = parse_event(event)
            if play:
                prev = self._play.get(box)
                if prev is None or prev[0] != play:
                    self._play[box] = (play, now)
                text, seen = self._play[box]
                if status == LIVE and BREAK_PLAY.search(text) and now - seen < self.break_play_secs:
                    status = BREAK
            self.status[box] = status
        return self.status

    def play_changed_since(self, box: int, t: float) -> bool:
        """True if a new play has shown up for this box since time t (game resumed)."""
        p = self._play.get(box)
        return bool(p and p[1] > t and not BREAK_PLAY.search(p[0]))
