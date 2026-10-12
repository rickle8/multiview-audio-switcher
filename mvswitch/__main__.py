"""Command line entry point.

    python -m mvswitch listen      # print what the mic hears (tune thresholds)
    python -m mvswitch test-keys   # cycle the audio through every box
    python -m mvswitch run         # the real thing
    python -m mvswitch devices     # list microphones
    python -m mvswitch scores      # show how each game is rated right now
"""
from __future__ import annotations

import argparse
import logging
import sys
import time

import yaml

from .remote import LAYOUTS, Navigator, make_remote

log = logging.getLogger("mvswitch")


def load_config(path: str) -> dict:
    with open(path) as f:
        return yaml.safe_load(f) or {}


def build_navigator(cfg: dict) -> Navigator:
    mv = cfg.get("multiview", {})
    layout = str(mv.get("layout", "4"))
    neighbors = mv.get("neighbors") if layout == "custom" else LAYOUTS[layout]
    neighbors = {int(k): v for k, v in neighbors.items()}
    return Navigator(make_remote(cfg.get("remote", {})), neighbors,
                     start_box=int(mv.get("start_box", 1)),
                     key_delay=float(mv.get("key_delay", 0.35)),
                     wake_key=mv.get("wake_key"), confirm_key=mv.get("confirm_key"))


def boxes_from(cfg: dict) -> dict[int, dict]:
    return {int(k): (v or {}) for k, v in (cfg.get("games") or {}).items()}


def build_verdict(cfg: dict):
    from .detector import Verdict

    d = cfg.get("detector", {})
    return Verdict(window_secs=d.get("window_secs", 8), ad_threshold=d.get("ad_threshold", 0.6),
                   game_threshold=d.get("game_threshold", 0.4),
                   min_ad_secs=d.get("min_ad_secs", 5), silence_dbfs=d.get("silence_dbfs", -55))


def cmd_devices(_cfg):
    import sounddevice as sd

    print(sd.query_devices())


def cmd_listen(cfg):
    from .detector import MicListener

    verdict = build_verdict(cfg)
    mic = MicListener(device=cfg.get("detector", {}).get("mic_device"))
    mic.start()
    print("Listening... Ctrl+C to stop.\n  ad%  = share of commercial-sounding audio in the window")
    while True:
        frame = mic.next_frame()
        if frame is None:
            continue
        state = verdict.add(frame)
        frac = verdict.ad_fraction()
        top = ", ".join(f"{n} {s:.2f}" for n, s in mic.last_top)
        pct = "  -- " if frac is None else f"{frac * 100:4.0f}%"
        print(f"{state:>6}  ad%={pct}  vol={frame.loud:5.1f}dB  game={frame.game:.2f} "
              f"ad={frame.ad:.2f}  | {top}")


def cmd_test_keys(cfg):
    nav = build_navigator(cfg)
    boxes = sorted(nav.neighbors)
    print(f"Assuming box {nav.current} is highlighted now. Cycling through {boxes}.")
    for box in boxes + [boxes[0]]:
        nav.go_to(box)
        print(f"  audio should now be on box {box}")
        time.sleep(3)


def cmd_scores(cfg):
    from .excitement import excitement
    from .feed import GameFeed

    games = boxes_from(cfg)
    feed = GameFeed(games)
    feed.poll(force=True)
    for box in sorted(games):
        info = feed.info.get(box)
        desc = info.describe() if info else feed.status.get(box, "not found")
        print(f"box {box}: {games[box].get('team', '?'):>4}  {desc:<28} "
              f"score {excitement(info, games[box])}")


def cmd_run(cfg):
    from .controller import Controller
    from .detector import MicListener
    from .feed import GameFeed

    nav = build_navigator(cfg)
    games = boxes_from(cfg)
    sw = cfg.get("switching", {})
    feed = GameFeed(games, poll_secs=sw.get("feed_poll_secs", 15)) if sw.get("use_feed", True) else None
    ctrl = Controller(nav, sorted(nav.neighbors), feed, games=games,
                      upgrade_margin=sw.get("upgrade_margin", 0.35),
                      min_dwell_secs=sw.get("min_dwell_secs", 45),
                      primary=sw.get("primary_box"), priority=sw.get("priority"),
                      settle_secs=sw.get("settle_secs", 6),
                      assumed_break_secs=sw.get("assumed_break_secs", 150),
                      require_feed_confirm=sw.get("require_feed_confirm", False),
                      return_to_primary=sw.get("return_to_primary", True))
    verdict = build_verdict(cfg)
    mic = MicListener(device=cfg.get("detector", {}).get("mic_device"))
    mic.start()
    log.info("Running. Audio assumed on box %s. Ctrl+C to stop.", nav.current)
    last = None
    while True:
        frame = mic.next_frame()
        if frame is None:
            continue
        state = verdict.add(frame)
        if state != last:
            log.info("Box %s sounds like: %s", nav.current, state)
            last = state
        if ctrl.step(state):
            mic.drain()
            verdict.reset()
            last = None


def main(argv=None):
    p = argparse.ArgumentParser(prog="mvswitch", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["run", "listen", "test-keys", "devices", "scores"])
    p.add_argument("-c", "--config", default="config.yaml")
    p.add_argument("-v", "--verbose", action="store_true")
    a = p.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if a.verbose else logging.INFO,
                        format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    cfg = {} if a.command == "devices" else load_config(a.config)
    try:
        {"run": cmd_run, "listen": cmd_listen, "test-keys": cmd_test_keys,
         "devices": cmd_devices, "scores": cmd_scores}[a.command](cfg)
    except KeyboardInterrupt:
        print("\nStopped.")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
