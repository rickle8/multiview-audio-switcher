from mvswitch.controller import Controller
from mvswitch.detector import AD, GAME, UNSURE, Frame, Verdict
from mvswitch.feed import BREAK, FINAL, LIVE, GameFeed, parse_event
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


def test_navigator_presses_and_tracks():
    r = DryRunRemote()
    nav = Navigator(r, LAYOUTS["2"], sleep=lambda s: None)
    nav.go_to(2)
    assert r.pressed == ["RIGHT"] and nav.current == 2


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
    assert parse_event(event("GB", "MIN"))[0] == LIVE
    assert parse_event(event("GB", "MIN", name="STATUS_HALFTIME"))[0] == BREAK
    assert parse_event(event("GB", "MIN", state="post", name="STATUS_FINAL"))[0] == FINAL


def test_feed_timeout_counts_as_break_then_expires():
    clock = Clock()
    board = {"events": [event("GB", "MIN", play="Timeout #1 by GB at 04:12.")]}
    f = GameFeed({1: {"league": "football/nfl", "team": "GB"}}, fetch=lambda l: board,
                 break_play_secs=150, poll_secs=0, clock=clock)
    assert f.poll()[1] == BREAK
    clock.t = 200
    assert f.poll()[1] == LIVE


# --- controller -----------------------------------------------------------
class FakeFeed:
    def __init__(self, status):
        self.status = status

    def poll(self):
        return self.status

    def play_changed_since(self, box, t):
        return False


def make(feed_status=None, **kw):
    clock = Clock()
    nav = Navigator(DryRunRemote(), LAYOUTS["4"], sleep=lambda s: None)
    feed = FakeFeed(feed_status) if feed_status is not None else None
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
