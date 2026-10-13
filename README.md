# Multiview Audio

When the game you're hearing in YouTube TV multiview goes to commercial, this moves the audio to the best other game that's still live, then comes back when the break is over.

There are two versions:

- **Android app** (recommended): runs entirely on your phone. Uses the phone's mic, talks to the TV over Wi-Fi, and keeps working with the screen off. Source in [`android/`](android).
- **PC version**: a Python script for a mini PC with a USB mic. Same logic, no UI. Docs below.

## Android app

### Install

1. On your phone, open this repo's **Releases** page and download `MultiviewAudio.apk` from the newest release.
2. Open it. Android will ask to allow installs from your browser; allow it, then Install.
3. New versions install right over the old one (same steps).

Every push to `main` that touches `android/` builds a new release automatically.

### Use it

1. **Settings tab:** pick Samsung TV or Roku, enter its IP address, open multiview on the TV, and tap **Test with the TV**. On a Samsung, accept the "Allow" prompt on the TV the first time.
2. **Games tab:** pick the layout (2, 3 or 4 games), then tap each box and choose the game from today's schedule. Star your main game. Optionally enter your pick against the spread (like `GB -3.5`).
3. **Live tab:** tap **Start switching**, set the phone near the TV, and leave it. Pause/stop from the notification.

Tap a box on the Live tab to move the audio there yourself, or lock it so nothing switches until you unlock.

### How it decides

- **Mic:** Google's YAMNet sound classifier (runs on the phone, offline) scores every second across 521 sound types. Combined with the sound's spectral shape and voice timbre, that's about 600 numbers per second for a classifier that learns your TV.
- **Repeat ads:** commercials you mark (and ones it's very sure about) are fingerprinted Shazam-style, so the next time one airs it's recognized in a few seconds.
- **Announcer voices:** it learns the announcers' voices during play; a different voice talking counts toward "commercial."
- **Sudden changes:** a brief dip in sound followed by something that sounds different makes it react in about 5 seconds instead of 8+.
- **Break timing:** right after a score, timeout, turnover or quarter end, a break is likely; right after a break ended, another is unlikely. It adjusts how sure it needs to be.
- **Live data:** ESPN play-by-play for the game you're hearing is checked every 5 seconds, with each play's real time. It spots touchdowns, field goals, official timeouts, the 2-minute warning, end of quarters, injuries and halftime, and switches *before* the ad starts. Stretches between plays teach it what the game sounds like.
- **Best game:** every live game gets a rating from closeness, time left, red zone, overtime, your pick, and your star. Commercial on your game means it goes to the highest rated live game, and it will also leave a blowout for a much better game mid-play.
- **Never loses track:** before each switch it presses up/left to reset the highlight to the top-left box.

### Building it yourself

GitHub builds it for you. To build locally: install Android Studio, download the model with
`curl -o android/app/src/main/assets/yamnet.tflite https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite`,
then open the `android/` folder.

---

# PC version


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

5. **Switches before the break** when ESPN shows a play that almost always leads to commercial on your game (touchdown, field goal, two-minute warning, end of quarter/half, injury, halftime), instead of waiting to hear the ad. The mic still catches anything the feed misses or reports late.
6. **Can't lose track of the highlight.** Before every switch it presses UP and LEFT to pin the highlight in the top-left box, then moves from there. Using the real remote no longer confuses it.

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

It first presses UP/LEFT to reset to the top-left box, then the audio should walk through every box and back. If it doesn't land on box 1 first, raise `home_presses`. If UP/LEFT at the edge wraps around or opens a menu on your TV, set `home: false` (it will then track position on its own, like before). If the first press only pops up the overlay, set `wake_key`. If your device needs OK to switch audio, set `confirm_key: SELECT`.

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

- Each switch takes about 2 seconds of key presses, and the audio briefly passes through box 1 on the way.
- The ESPN feed can lag, so a proactive switch may land a few seconds into the break rather than right at it.
- Ads with crowd noise (beer, cars, sports betting), studio segments and halftime shows can fool the mic. `require_feed_confirm: true` cuts false switches at the cost of slower reactions.
- ESPN data runs 10 to 60 seconds behind the broadcast.
- Return-to-primary is a guess based on typical break length plus the feed showing new plays.

## Tests

```bash
pip install pytest && pytest
```
