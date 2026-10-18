# Multiview Audio: how it decides (v1.0.79)

Built for one setup: a Samsung QN75Q8F, YouTube TV multiview with 4 games (2x2), and a Galaxy S21 on a stand facing the TV doing everything (listening, watching, pressing the TV's buttons). College football Saturdays, NFL Sundays.

**The short version:** every second the mic decides whether the box you're hearing is a commercial. If it is, the app presses arrows to move the sound to the best other game that's on, then goes back when your game returns. ESPN and the camera help it decide when and where, and anything you tap wins. You don't have to touch the camera phone: your own phone is the remote (section 8).

---

## 1. What runs, and how often

| Part | How often | What it does |
|---|---|---|
| Mic | every second | Scores the sound: game or commercial |
| ESPN | scoreboard every 10 s; plays every 5 s for your box, 8 s for the others | Status, plays, game clock for all 4 games |
| Camera | about 4 frames a second | Reads each box's score bug; finds the TV |
| Decision | every second | Stay, switch, or go back |
| TV | only when switching | Arrow presses, 0.35 s apart |

When you tap Start: it turns the TV on if it's off, connects, and starts listening. It assumes the sound is on box 1, or on the box it was on if you stopped and started again within 20 minutes with the same games.

## 2. Knowing where the sound is

YouTube TV never tells the app which box has the sound, so the app keeps count itself:
- Every arrow it presses moves its count (edges don't wrap). The arrows on the Remote tab count too.
- **Your TV remote:** YouTube TV flashes a white outline on the box the sound moves to. Every time the app moves the sound itself, the camera checks the outline flashed on the right box. Once it has been right on the last 3 moves (and wrong on none of the last 5), it follows your TV-remote moves on its own ("Camera: you moved the sound to box N. Going with it."). Until then it asks "Camera saw the sound move to box N. Is it there?" on both phones, and your answers count toward proving it. If it ever "moves" more than 3 times in 2 minutes, it's misreading something and goes back to asking.
- **If it's ever wrong, tap the box and pick "Sound is already on this box"** (on either phone). That's the fix for everything downstream.
- Homing (pressing up/left to reset to box 1) is off, because it opens YouTube TV's menus.

## 3. Is it a commercial? (the mic)

**What it listens for.** Google's YAMNet sound classifier runs on the phone. Before it's taught, crowd noise, cheering and announcer talk count as game; music and jingles count as commercial. Once it has 25+ seconds of each from your taps, a model trained on your TV and your room takes over. It retrains every 10 new seconds.

**Commercials it's heard before.** Every "It's an ad" tap saves that commercial's audio fingerprint. When it hears one again it's sure within about 4-5 seconds.

**The call.** It looks at the last 10 seconds. At the default sensitivity, about 62% or more ad-like for 8 seconds in a row = commercial; 42% or less = game. ESPN changes how quick it is:

| Situation | Needs | Why |
|---|---|---|
| Normal | 8 s | |
| A break-likely play just happened (score, timeout, turnover, quarter end) | 7 s | Break expected |
| Just switched to this game | 4 s | If it's also in commercial, leave fast |
| Sound changed suddenly (the cut to commercial) | 5 s | |
| Game clock running | 16 s, and more ad-like | TV breaks only come with the clock stopped |
| Just back from a break | 16 s | Another one is unlikely |

**It also learns on its own:** audio between plays that came less than 50 seconds apart is game, and replayed commercials it recognizes are commercials.

**Your buttons:**
- **It's an ad:** learns the audio since the cut, saves a fingerprint, and moves now.
- **It's the game:** learns the last 8 seconds and next 12, deletes a fingerprint that just fired wrongly, and tells the switching logic this game is on. For 4 minutes (or until it leaves that game for a commercial), the camera and ESPN can't call it a commercial or show it as BREAK.

## 4. What ESPN tells it

**Status** is ESPN's own: live, halftime, end of a quarter, final, pregame. (Changed in v1.0.75: a timeout, touchdown or kickoff no longer flips a game to BREAK for 2.5 minutes. That's what made live games show BREAK.) Halftime counts as a show, not a commercial (section 6), and the box says HALFTIME.

**Your TV runs behind ESPN.** It assumes 40 seconds until it learns the real delay, from commercials that start right after a timeout or from the crowd's roar after big plays.

**For each game it works out what your TV is showing right now:**
- **About to go to commercial:** ESPN has a break play (TD, field goal, official/TV timeout, 2-minute warning, quarter or half end, injury) that your TV hasn't reached yet.
- **Live:** the game clock is running, or your TV just showed an ordinary play (up to 30 s ago).
- **In commercial:** your TV showed a break play within the typical break length plus 40 s (2.5 minutes normally, about 4.5 after a score), or there have been no plays for 85+ seconds with the clock stopped.
- **Unknown:** none of the above.

**On the game you're hearing it does two things:**
- **Guards:** if a play just happened (35 s, TV time) or the clock is running, an ad-sounding moment doesn't move you ("Sounded like an ad, but...").
- **Warns:** after a TD, field goal, official timeout, 2-minute warning or quarter end it logs "break coming" and waits for the commercial sound, then goes right away. No commercial within 2.5-4 minutes, it lets it go. At halftime it goes once your TV gets there, commercial or not (the studio show isn't the game).

## 5. What the camera tells it

**Lining up.** It finds the TV by motion (the games move, the room doesn't). It checks again every minute and 2 seconds after a bump, and only moves the grid if the TV really moved. Turning the phone triggers a full find after it sits still 3 seconds.

**Reading the score bug.** One box every half second (each box about every 2 s), it reads the text: clock with quarter, down and distance, your teams' names, scores matching ESPN.
- Read in the last 10 s: that game is on.
- Nothing for 45 s on a box it normally reads well (4+ reads in the minute before), while it still reads the other boxes: commercial. 45 s is longer than replays and close-ups.

**The ticker:** some networks (ESPN's BottomLine) run other games' scores and headlines along the bottom. Each line it reads comes with where it sits, so a ticker line (a league name like NCAAF, two of your games in one line, or along the very bottom another game's score, "final" or a headline) never counts as the box's own score bug or says which game it is. A ticker with no score bug means the network's own programming is on (tickers don't run in commercials): a show, not the game and not a commercial. The score bug's own HALF or FINAL doesn't count as play either.

**Commercial text:** web addresses, APR or MSRP, "see dealer", "closed course", "ask your doctor", 1-800 numbers, two of "shop now" / "lease" / "limited time" and the like, with no score bug in the same read: that box is in a commercial, no waiting.

**The score bug's shape** (learned per box) only counts once it has matched the text: present twice during play and gone once in a commercial. If something surer contradicts it, it's relearned.

**The field:** in each box it measures how much of the picture is football-field green. A wide shot of the field in the last 8 s says the game is on (never the other way round, since close-ups and replays go long stretches without it). It counts on a box once it has matched the score text or the sound: field during the game 3 times, none in a commercial once.

**How the camera's word is used:**
- Other games: "on" makes it a good place to go and marks your game as back. "Commercial" means skip it.
- Your game: no score bug + ad-sounding = sure commercial (beats ESPN's guards). No score bug + unsure sound = likely commercial. Score bug read in the last 6 s + ad-sounding = stays. **It never turns game audio into a commercial.**

## 6. When it switches (the game you're hearing)

Checked in this order:
1. Switched in the last 6 seconds: wait.
2. Commercial heard (or confirmed by the camera):
   - Bounced twice in 75 seconds (every game in commercial): sit on this one for 45 s, but jump as soon as ESPN shows any game back on.
   - ESPN warned of a break your TV hasn't reached yet: wait.
   - A commercial it's sure of (fingerprint, or the camera agrees): go now.
   - ESPN guard (play just happened, clock running): stay.
   - Otherwise go.
3. **Where to, in the order you want:**
   - a game being played (sure ones first, then ones it can't vouch for), most exciting first (close, late, red zone, overtime)
   - no game being played anywhere: a show (another game's halftime show, or a box where the camera sees the network's ticker without the game), the camera's sure ones first
   - nothing but commercials: the one whose break started first (at least 60 s ago)
   - nothing at all: it stays
4. **Halftime on your game:** once your TV reaches it, it goes to a game being played. If none is, it stays for the halftime show.
5. **Parked on a show** because nothing was being played (or the camera has seen only the network's show there for a minute): it leaves for a live game as soon as there is one. A commercial during the show is handled like any other.
6. Game heard: if the game you were watching is back, go back to it.
7. Jumping to a more exciting game mid-play ("hopping"): off.

## 7. Going back to your game

When it leaves your game for a commercial, it remembers it (not the stops along the way). It goes back once it's been at least 15 s since the last switch and:
- the camera reads your game's score bug again (fastest), or
- ESPN shows a play since the break that your TV has had time to show, or
- the break has gone on longer than normal (2.5-8 minutes; halftime 20).

At halftime it remembers your game even if it parks on its halftime show and then leaves for a live game, so it comes back for the second half.

Tapping a box, "Sound is already on this box," or Remote-tab arrows drops the plan to go back. Wherever you put the sound is what it comes back to after the next commercial.

## 8. Your phone is the remote

The camera phone serves a control page on your home Wi-Fi. Scan the code in Settings > Camera (or on the camera setup screen) with your own phone and bookmark it. It has:
- the four boxes with their status, which one has the sound, and what the camera says about each
- **It's an ad** / **It's the game**
- tap a box: play it, "sound is already on this box", lock it
- start, stop, pause, and the camera's "Is it there?" question
- what the camera sees, with the boxes it reads drawn on it, and "Find the TV again"
- the activity log, and **Copy diagnostics** to paste into a chat

Both phones need the same Wi-Fi, and the app open on the camera phone. Only a device with the link's key can use it.

## 9. On your TV, these do nothing

- **Open YouTube TV on start** and **pause when YouTube TV isn't on screen:** your TV won't give the app its app list, so these never start ("YouTube TV app: (not found yet)" in diagnostics). The same goes for "On the TV" and the volume readout.
- Roku support, 2- and 3-game layouts, "something else" boxes, hopping, HDMI input teaching: unused by you, and they don't run during games.

## 10. Weak spots for your setup

1. **The camera is untested on your TV.** Distance, glare and each network's bug design decide how much it reads. Diagnostics show, per box, what it reads, whether the field and score bug shape are proven, and how many of the app's moves the outline confirmed.
2. **ESPN's college play-by-play is late,** often 30-90 seconds and sometimes in bursts. That's why the mic leads and ESPN mostly guards and picks targets. Going back to your game is slow without the camera.
3. **The start position is a guess** (box 1, or where it was). If it's wrong, every switch after it is wrong until you tap "Sound is already on this box," or (once the outline is proven) press an arrow on the TV remote so the camera sees where the sound is.
4. **Your TV's delay** is assumed to be 40 s until it learns it from a few timeouts.
5. **The mic's learning resets** if the app's data gets cleared. A few taps of each button retrains it.

## 11. Reading the Activity log

| Line | Meaning |
|---|---|
| Commercial on box N → box M | Mic heard a commercial, moved to M |
| Recognized a commercial it's heard before on box N → box M | Fingerprint match |
| Sounds like a commercial and the camera sees no score bug on box N → box M | Mic and camera agreed |
| Sounded like an ad, but box N just had a play / clock is running / camera sees the game. Staying. | A guard held it |
| ESPN: break coming on box N (...). Waiting for the commercial. | Warned; leaves when the sound turns |
| Commercial started on box N after "..." → box M | The warned break came |
| ...every game is in commercial, box M should be back first | Nothing live; went where the break started earliest |
| Commercials everywhere. Staying on box N for a bit. | Bounced twice in 75 s |
| ...no game is being played, box M has the halftime show → box M | Nothing live; a show beats a commercial |
| ...No other game is being played: staying for the halftime show. | Your game's halftime, nothing live elsewhere |
| Box M is being played; leaving the halftime show on box N → box M | A live game came back |
| Box N is back from commercial → box N | Went back to your game |
| Camera: box N's score bug was learned wrong (...). Learning it again. | Shape didn't match the text or your tap |
| Camera: the TV moved in the picture. Lined it up again. | The minute check or a bump moved the grid |
| Camera was right/wrong: the sound moved to box N. | Your answer to the outline question |
| Camera: you moved the sound to box N. Going with it. | Followed your TV remote (outline proven) |
