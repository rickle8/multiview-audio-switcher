from mvswitch.controller import Controller
from mvswitch.detector import AD, GAME, UNSURE, Frame, Verdict
from mvswitch.excitement import closeness, excitement, progress
from mvswitch.feed import BREAK, FINAL, LIVE, GameFeed, GameInfo, parse_event
import pytest

from mvswitch.remote import LAYOUTS, DryRunRemote, Navigator, key_path


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


def event(team_a, team_b, state="in", name="STATUS_IN_PROGRESS", play=None):
    comp = {"status": {"type": {"state": state, "name": name, "completed": state == "post"}},
            "competitors": [{"team": {"abbreviation": team_a}}, {"team": {"abbreviation": team_b}}]}
    if play:
        comp["situation"] = {"lastPlay": {"text": play}}
    return {"competitions": [comp]}


# --- navigation -----------------------------------------------------------
def test_key_paths():
    grid = LAYOUTS["4"]
    assert key_path(grid, 1, 1) == []
    assert key_path(grid, 1, 2) == ["RIGHT"]
    assert len(key_path(grid, 1, 4)) == 2
    assert key_path(LAYOUTS["3"], 3, 1) == ["LEFT"]


def test_navigator_presses_and_tracks_without_homing():
    r = DryRunRemote()
    nav = Navigator(r, LAYOUTS["2"], home=False, sleep=lambda s: None)
    nav.go_to(2)
    assert r.pressed == ["RIGHT"] and nav.current == 2


@pytest.mark.parametrize("layout", ["2", "3", "4"])
def test_homing_reaches_every_box_from_any_real_position(layout):
    from mvswitch.remote import simulate

    grid = LAYOUTS[layout]
    for actual in grid:            # where the highlight really is
        for target in grid:
            r = DryRunRemote()
            nav = Navigator(r, grid, start_box=1, sleep=lambda s: None)
            nav.current = None       # pretend we lost track completely
            nav.go_to(target)
            assert simulate(grid, actual, r.pressed) == target


def test_homing_rejects_bad_settings():
    with pytest.raises(ValueError):  # UP alone can't reach box 1 from box 2
        Navigator(DryRunRemote(), LAYOUTS["4"], home_keys=("UP",), sleep=lambda s: None)


# --- detector -------------------------------------------------------------
def feed_frames(v, n, game, ad, start=0):
    state = None
    for i in range(n):
        state = v.add(Frame(t=start + i, game=game, ad=ad, loud=-20))
    return state


def test_verdict_game_then_ad():
    v = Verdict(window_secs=8, min_ad_secs=3)
    assert feed_frames(v, 10, game=0.8, ad=0.05) == GAME
    # a couple seconds of music isn't enough
    assert feed_frames(v, 2, game=0.0, ad=0.9, start=10) == GAME
    assert feed_frames(v, 12, game=0.0, ad=0.9, start=12) == AD


def test_verdict_ignores_silence():
    v = Verdict()
    for i in range(10):
        v.add(Frame(t=i, game=0, ad=0.9, loud=-80))
    assert v.state == UNSURE


# --- feed -----------------------------------------------------------------
def test_parse_event():
    assert parse_event(event("GB", "MIN")).status == LIVE
    assert parse_event(event("GB", "MIN", name="STATUS_HALFTIME")).status == BREAK
    assert parse_event(event("GB", "MIN", state="post", name="STATUS_FINAL")).status == FINAL


def test_parse_scores_clock_redzone():
    e = event("GB", "MIN")
    c = e["competitions"][0]
    c["status"].update(period=4, clock=125.0)
    c["competitors"][0]["score"] = "24"
    c["competitors"][1]["score"] = "21"
    c["situation"] = {"isRedZone": True}
    info = parse_event(e, "football/nfl")
    assert info.scores == {"GB": 24, "MIN": 21} and info.period == 4
    assert info.red_zone and info.clock == 125.0


def test_feed_timeout_counts_as_break_then_expires():
    clock = Clock()
    board = {"events": [event("GB", "MIN", play="Pass complete to Reed for 8 yards.")]}
    f = GameFeed({1: {"league": "football/nfl", "team": "GB"}}, fetch=lambda l: board,
                 break_play_secs=150, poll_secs=0, clock=clock)
    assert f.poll()[1] == LIVE
    board["events"] = [event("GB", "MIN", play="Timeout #1 by GB at 04:12.")]
    assert f.poll()[1] == BREAK
    clock.t = 200
    assert f.poll()[1] == LIVE


# --- controller -----------------------------------------------------------
class FakeFeed:
    def __init__(self, status, info=None):
        self.status = status
        self.info = info or {}
        self.plays = {}

    def latest_play(self, box):
        return self.plays.get(box)

    def poll(self):
        return self.status

    def play_changed_since(self, box, t):
        return False


def make(feed_status=None, info=None, **kw):
    clock = Clock()
    nav = Navigator(DryRunRemote(), LAYOUTS["4"], home=False, sleep=lambda s: None)
    feed = FakeFeed(feed_status, info) if feed_status is not None else None
    c = Controller(nav, [1, 2, 3, 4], feed, clock=clock, **kw)
    return c, nav, clock


def test_switches_on_ad_and_skips_games_in_break_or_final():
    c, nav, clock = make({1: LIVE, 2: FINAL, 3: BREAK, 4: LIVE})
    clock.t = 100
    assert c.step(AD) is True
    assert nav.current == 4


def test_settle_time_blocks_flapping():
    c, nav, clock = make()
    clock.t = 100
    c.step(AD)
    first = nav.current
    clock.t = 102
    assert c.step(AD) is False and nav.current == first


def test_hops_until_live_game_then_returns_to_primary():
    c, nav, clock = make(assumed_break_secs=150, settle_secs=6)
    clock.t = 100
    c.step(AD)            # box 1 in commercial -> box 2
    assert nav.current == 2
    clock.t = 110
    c.step(AD)            # box 2 also in commercial -> box 3
    assert nav.current == 3
    clock.t = 120
    assert c.step(GAME) is False and nav.current == 3  # box 1 break not over
    clock.t = 260
    assert c.step(GAME) is True and nav.current == 1


def test_all_in_commercial_stays_put():
    c, nav, clock = make({1: LIVE, 2: BREAK, 3: BREAK, 4: FINAL})
    clock.t = 100
    assert c.step(AD) is False and nav.current == 1


def test_require_feed_confirm():
    c, nav, clock = make({1: LIVE, 2: LIVE, 3: LIVE, 4: LIVE}, require_feed_confirm=True)
    clock.t = 100
    assert c.step(AD) is False


# --- excitement -----------------------------------------------------------
def nfl(a, b, period, clock, status=LIVE, red_zone=False):
    return GameInfo(status=status, league="football/nfl", period=period, clock=clock,
                    scores={"AAA": a, "BBB": b}, red_zone=red_zone)


def test_progress():
    assert progress(nfl(0, 0, 1, 900)) == 0
    assert abs(progress(nfl(0, 0, 4, 0)) - 1) < 1e-9
    assert progress(nfl(0, 0, 5, 600)) > 1


def test_close_late_beats_close_early_beats_blowout():
    close_late = excitement(nfl(24, 21, 4, 120))
    close_early = excitement(nfl(7, 3, 1, 600))
    blowout_late = excitement(nfl(38, 3, 4, 120))
    assert close_late > close_early > blowout_late


def test_red_zone_overtime_importance_and_final():
    base = excitement(nfl(17, 14, 3, 300))
    assert excitement(nfl(17, 14, 3, 300, red_zone=True)) > base
    assert excitement(nfl(20, 20, 5, 400)) > excitement(nfl(20, 17, 4, 30))
    assert excitement(nfl(17, 14, 3, 300), {"importance": 2}) == 2 * base
    assert excitement(nfl(17, 14, 4, 0, status=FINAL)) == 0


def test_pick_against_spread_counts_as_close():
    g = nfl(10, 24, 4, 300)  # BBB up 14
    assert closeness(g, "AAA +13.5") > 0.9
    assert closeness(g, "AAA +13.5") > closeness(g)


def test_commercial_switches_to_most_exciting_game():
    info = {1: nfl(10, 7, 2, 300), 2: nfl(35, 3, 4, 200),
            3: nfl(21, 20, 4, 90), 4: nfl(14, 10, 1, 500)}
    c, nav, clock = make({b: LIVE for b in info}, info)
    clock.t = 100
    c.step(AD)
    assert nav.current == 3


def test_upgrade_from_blowout_after_dwell():
    info = {1: nfl(38, 3, 4, 400), 2: nfl(24, 24, 4, 100), 3: nfl(0, 0, 1, 900),
            4: nfl(0, 0, 1, 900)}
    c, nav, clock = make({b: LIVE for b in info}, info, min_dwell_secs=45)
    clock.t = 100
    assert c.step(GAME) is True and nav.current == 2
    # just switched: a better game appearing doesn't cause instant ping-pong
    info[3] = nfl(28, 28, 5, 500)
    clock.t = 120
    assert c.step(GAME) is False
    clock.t = 160
    assert c.step(GAME) is True and nav.current == 3


def test_returns_to_important_game_after_break():
    info = {1: nfl(14, 10, 3, 400), 2: nfl(14, 10, 3, 400), 3: nfl(0, 30, 3, 400),
            4: nfl(0, 30, 3, 400)}
    c, nav, clock = make({b: LIVE for b in info}, info, assumed_break_secs=150)
    c.games = {1: {"importance": 2.0}}
    clock.t = 100
    c.step(AD)
    assert nav.current == 2
    clock.t = 160
    assert c.step(GAME) is False  # box 1 still assumed on break
    clock.t = 260
    assert c.step(GAME) is True and nav.current == 1


# --- proactive ------------------------------------------------------------
def test_switches_right_away_on_touchdown_even_while_hearing_game():
    info = {b: nfl(14, 10, 3, 400) for b in (1, 2, 3, 4)}
    c, nav, clock = make({b: LIVE for b in info}, info)
    clock.t = 100
    c.feed.plays[1] = ("J.Love pass to C.Watson for 22 yards, TOUCHDOWN.", 95)
    assert c.step(GAME) is True and nav.current != 1
    assert c.break_until[1] > clock.t


def test_proactive_ignores_ordinary_and_stale_plays_and_fires_once():
    info = {b: nfl(14, 10, 3, 400) for b in (1, 2, 3, 4)}
    c, nav, clock = make({b: LIVE for b in info}, info)
    clock.t = 100
    c.feed.plays[1] = ("Run up the middle for 3 yards.", 99)
    assert c.step(GAME) is False
    c.feed.plays[1] = ("Field goal is GOOD.", 10)   # seen 90s ago
    assert c.step(GAME) is False
    c.feed.plays[1] = ("Two-Minute Warning", 99)
    assert c.step(GAME) is True
    nav.current = 1
    clock.t = 200
    assert c.step(GAME) is False  # same play doesn't trigger twice


def test_halftime_status_triggers_switch():
    info = {b: nfl(14, 10, 2, 0) for b in (1, 2, 3, 4)}
    info[1].status_name = "STATUS_HALFTIME"
    status = {1: BREAK, 2: LIVE, 3: LIVE, 4: LIVE}
    c, nav, clock = make(status, info)
    clock.t = 100
    assert c.step(GAME) is True and nav.current != 1


def test_feed_ignores_plays_already_on_board_at_startup():
    clock = Clock()
    board = {"events": [event("GB", "MIN", play="TOUCHDOWN GB")]}
    f = GameFeed({1: {"league": "football/nfl", "team": "GB"}}, fetch=lambda l: board,
                 poll_secs=0, clock=clock)
    clock.t = 1000
    f.poll()
    assert f.status[1] == LIVE
    c = Controller(Navigator(DryRunRemote(), LAYOUTS["2"], home=False,
                             sleep=lambda s: None), [1, 2], f, clock=clock)
    assert c._break_coming(1, clock.t) is None
