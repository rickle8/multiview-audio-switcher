# Multiview Audio Switcher

Listens to your TV through a USB mic. When the game you're hearing in YouTube TV multiview goes to commercial, it moves the audio to another game that's still live, then back to your main game when its break should be over.

Runs on a mini PC (or any Linux/Windows/Mac box) on the same network as the TV.

## How it works

1. **Mic + YAMNet** (Google's sound classifier) scores each second of audio as "game-like" (crowd, cheering, babble) or "ad-like" (music, jingles, clean voiceover). A rolling 8 second window with hysteresis decides *game* vs *commercial*.
2. **ESPN live data** (optional, on by default) adds a second opinion: which games are live, final, at halftime, or just had a timeout/score/injury.
3. **Remote control** presses arrow keys on the TV to move the multiview highlight, which moves the audio.
4. **Game scoring** rates every live game so it switches to the best one, not just the next one:
   - **Closeness**: tie = max, blowout = near zero (scaled per sport)
   - **Time left**: a close 4th quarter counts far more than a close 1st
   - **Red zone** and **overtime** bonuses
   - **Your pick**: set `pick: "MIN +3.5"` and a game sweating your spread counts as close
   - **Importance**: your weight per game (favorite team = 2.0)

   It also upgrades mid-game (no commercial needed) when another game becomes clearly better, after staying at least `min_dwell_secs`. Giving your main game a higher importance is how it gets back to it after a break.

Multiview only plays one game's audio at a time, so it can only *hear* the game you're on. When it switches, it listens to the new game; if that one is also in commercial, it hops again.

## Setup

```bash
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp config.example.yaml config.yaml   # then edit it
```

On Linux you may also need `sudo apt install libportaudio2`.

### 1. Pick your remote type

YouTube TV has to be running on the device you control:

| Where YouTube TV runs | `remote.type` | Notes |
|---|---|---|
| Samsung TV app | `samsung` | First run pops up "Allow" on the TV. Accept it. |
| Roku | `roku` | Turn on Settings > System > Advanced > Control by mobile apps |
| Google TV / Chromecast / Fire TV | `adb` | Turn on developer options + network debugging, install `adb` |

SmartThings isn't used because its cloud round trip adds lag and its remote key support is limited. The local connection is faster.

### 2. Map the boxes

Set `multiview.layout` to `2`, `3` or `4` and list each game under `games` in reading order (left to right, top to bottom). Team is the ESPN abbreviation (GB, MIN, KC...).

### 3. Test the keys

Start multiview with audio on box 1, then:

```bash
python -m mvswitch test-keys
```

The audio should walk through every box and back. If the first press only pops up the overlay, set `wake_key`. If your device needs OK to switch audio, set `confirm_key: SELECT`.

### 4. Tune the mic

```bash
python -m mvswitch devices   # find your mic
python -m mvswitch listen
```

Watch the `ad%` column during game play and during commercials, then adjust `ad_threshold` / `game_threshold` if needed. Put the mic near the TV speaker, away from where people talk.

### 5. Check the ratings

```bash
python -m mvswitch scores
```

Shows each box's score, clock, and rating right now. Tweak `importance`, `pick`, and `upgrade_margin` until the order matches what you'd pick.

### 6. Run it

```bash
python -m mvswitch run
```

To test detection without touching the TV, set `remote.type: dry_run`.

## Known limits

- It tracks which box is highlighted by itself. If you press arrows on the real remote, it loses track. Put the highlight back on the box it thinks it's on, or restart with the right `start_box`.
- Ads with crowd noise (beer, cars, sports betting), studio segments and halftime shows can fool the mic. `require_feed_confirm: true` cuts false switches at the cost of slower reactions.
- ESPN data runs 10 to 60 seconds behind the broadcast.
- Return-to-primary is a guess based on typical break length plus the feed showing new plays.

## Tests

```bash
pip install pytest && pytest
```
