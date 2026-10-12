"""Scores how worth-watching each game is right now.

score = importance x (base + closeness x lateness boost + red zone + overtime)

- closeness: 1.0 for a tie, 0 once the margin hits the sport's "blowout" size
- lateness: grows through the game, so a close 4th quarter beats a close 1st
- pick: optional against-the-spread line ("GB -3.5"); a game sweating your
  pick counts as close even if the straight-up score isn't
- importance: your own weight per game (favorite team, pick'em, rivalry)
"""
from __future__ import annotations

import re

from .feed import BREAK, LIVE, GameInfo

# league prefix -> (regulation periods, seconds per period or None, blowout margin)
SPORTS = {
    "football": (4, 900, 17),
    "basketball/mens-college-basketball": (2, 1200, 15),
    "basketball/womens-college-basketball": (4, 600, 15),
    "basketball/wnba": (4, 600, 15),
    "basketball": (4, 720, 15),
    "hockey": (3, 1200, 3),
    "baseball": (9, None, 4),
    "soccer": (2, 2700, 2),
}

PICK = re.compile(r"^\s*([A-Za-z]+)\s*([+-]?\d+(?:\.\d+)?)?\s*$")


def sport_params(league: str):
    for prefix in sorted(SPORTS, key=len, reverse=True):
        if league.startswith(prefix):
            return SPORTS[prefix]
    return SPORTS["football"]


def progress(info: GameInfo) -> float:
    """0 at kickoff, 1 at end of regulation, >1 in overtime/extras."""
    periods, secs, _ = sport_params(info.league)
    if info.period <= 0:
        return 0.0
    if secs is None or info.clock is None:
        return info.period / periods
    done = (info.period - 1) + (1 - min(max(info.clock, 0), secs) / secs)
    return done / periods if info.period <= periods else 1 + (info.period - periods) * 0.1


def closeness(info: GameInfo, pick: str | None = None) -> float:
    if len(info.scores) != 2:
        return 0.5
    _, _, blowout = sport_params(info.league)
    (a, sa), (b, sb) = info.scores.items()
    close = max(0.0, 1 - abs(sa - sb) / blowout)
    m = PICK.match(pick or "")
    if m and m.group(2) is not None:
        team, line = m.group(1).upper(), float(m.group(2))
        if team in info.scores:
            mine = info.scores[team]
            other = sb if team == a else sa
            ats = abs(mine + line - other)  # distance from the cover line
            close = max(close, max(0.0, 1 - ats / blowout))
    return close


def excitement(info: GameInfo | None, game_cfg: dict | None = None) -> float:
    cfg = game_cfg or {}
    importance = float(cfg.get("importance", 1.0))
    if info is None:
        return 0.5 * importance  # no data: neutral
    if info.status not in (LIVE, BREAK):
        return 0.0
    p = progress(info)
    c = closeness(info, cfg.get("pick"))
    score = 0.3 + c * (0.4 + 1.3 * min(p, 1.0) ** 2)
    if info.red_zone:
        score += 0.4
    if p > 1 and c > 0.5:
        score += 0.8  # close game in overtime
    return round(score * importance, 3)
