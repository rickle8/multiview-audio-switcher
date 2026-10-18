# Multiview Audio: how it decides (v1.0.89)

Built for one setup: a Samsung QN75Q8F, YouTube TV multiview with 4 games (2x2), and a Galaxy S21 on a stand facing the TV doing everything (listening, watching, pressing the TV's buttons). College football Saturdays, NFL Sundays.

**The short version:** every second the mic and the camera together decide whether the box you're hearing is a commercial (section 5, "Sound and camera, paired"). If it is, the app presses arrows to move the sound to the best other game that's on, then goes back when your game returns. ESPN helps it decide when and where, and anything you tap wins. You don't have to touch the camera phone: your own phone is the remote (section 8).

---

## 1. What runs, and how often

| Part | How often | What it does |
|---|---|---|
| Mic | every second | Scores the sound: game or commercial |
| ESPN | scoreboard every 10 s; plays every 5 s for your box, 8 s for the others | Status, plays, game clock for all 4 games |
| Camera | about 4 frames a second; a close look at each box about every 2 s | Score bug text, yard lines, field green, commercial text; finds the TV |
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

**The call.** It looks at the last 8 seconds. At the default sensitivity, about 62% or more ad-like for 6 seconds in a row = commercial; 42% or less = game. In all, about 10 seconds from the cut to commercial to the switch (14 before v1.0.81). ESPN changes how quick it is:

| Situation | Needs | From the cut | Why |
|---|---|---|---|
| Normal | 6 s | about 10 s | |
| ESPN warned a break is coming (TD, field goal, timeout, 2-minute warning, quarter end) | 3 s, a bit less ad-like | about 6 s | Break expected |
| A break-likely play just happened (turnover, punt) | 5 s | about 8 s | |
| Sound changed suddenly (the cut to commercial) | 4 s | about 7 s | |
| Just switched to this game | 3 s | | If it's also in commercial, leave fast |
| Game clock running | 16 s, and more ad-like | | TV breaks only come with the clock stopped |
| Just back from a break | 16 s | | Another one is unlikely |

The camera can make it quicker still: see "gone" in section 5.

**It also learns on its own:** audio between plays that came less than 50 seconds apart is game, and replayed commercials it recognizes are commercials.

**Your buttons:**
- **It's an ad:** learns the audio since the cut, saves a fingerprint, and moves now. If no game looks better, it goes to the best other game that isn't over anyway (pregame included), unless every one shows commercial text.
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
- **Warns:** after a TD, field goal, official timeout, 2-minute warning or quarter end it logs "break coming", gets quicker on the sound, and goes the moment it sounds like a commercial, past the ESPN guards. It doesn't wait for its own guess of when the TV gets there (that guess rests on the TV's delay, which can be off by a lot), only for the first 10 s after ESPN posts it. No commercial within 2.5-4 minutes, it lets it go. At halftime it goes once your TV gets there, commercial or not (the studio show isn't the game).

## 5. What the camera tells it

**Lining up.** It finds the TV by motion (the games move, the room doesn't). It checks again every minute and 2 seconds after a bump. After a bump it moves the grid at once; on the minute check only when a second look 10 s later agrees, and never for a shift under about 2.5% of the picture (a box on a still graphic makes the motion wobble). Turning the phone triggers a full find after it sits still 3 seconds.

**The picture.** 4K when the phone allows it (each box gets about twice the detail of 1080p), 15 frames a second (less heat over a long game, and exposures that don't band on the TV), the phone's best noise reduction and sharpening. Focus and exposure are measured on the TV itself, sized to it, and refreshed every 10 minutes and whenever the TV moves in the picture. Exposure is also nudged a third of a stop at a time so the TV's whites (the score bug's lettering, the yard lines) don't blow out and dark scenes don't go murky. The picture on your phone's camera view is in color at 1280 x 720.

**The close look.** One box every half second (each box about every 2 s), straightened out at 1280 x 720 (960 x 540 from a 1080p camera). It reads the text and looks for yard lines.

**What counts as the game being played:** that game's own teams and a score at or near ESPN's current one. NFL bugs on FOX and CBS show logos, not names, so a score at or near ESPN's with down and distance ("3RD & 8", "3RD DOWN"), or with a quarter and clock, counts on its own. Common misreads in the bug's small type are fixed first ("15T" for 1ST, "3R0" for 3RD, a clock that lost its colon) (your TV runs behind ESPN, so up to 8 points lower or 3 higher counts as near). Any of: ESPN's exact score with a team or the clock; a team, a near score and the clock or quarter (or both teams); both teams with the clock or quarter; a team with down and distance. A team name alone (a promo for the game) isn't enough, and the score bug's own HALF or FINAL isn't play.
- Read in the last 10 s: that game is on.
- **Gone:** on a box where it reads the bug nearly every time (60%+ of reads), missing it 8 s or more, enough reads in a row that it isn't a misread, while it still reads the other boxes. A cut to commercial looks like that. Some replays drop the bug too, so it only backs up the sound: an ad-leaning sound becomes a commercial and an ad-sounding one a sure one (section 6), a few seconds sooner than the sound alone.
- Nothing for 45 s on a box it normally reads well (4+ reads in the minute before it went), while it still reads the other boxes: leans commercial, for up to 5 minutes. Past that it has more likely lost the bug (a graphic over it) than the break is still going.
- **At halftime** the score bug is off for much of the studio show, so it being gone means nothing there; commercial text still counts.

**The ticker:** some networks (ESPN's BottomLine) run other games' scores and headlines along the bottom, sometimes over commercials too, so a ticker says nothing about the box: not the game, not a commercial, not a show. Each line comes with where it sits, and ticker lines never count toward the game: a league name like NCAAF, two of your games in one line, another game's score, "final" or a headline along the very bottom, or anything in the band where the text keeps changing read after read (it scrolls or flips). A line that's nothing but score bug (down and distance, or quarter and clock, with at most one other word) is never ticker, inside a band or not. The score bug never makes that band: lines with the game's own teams, and its clock, quarter and down and distance, are left out, one change (a "review" banner) isn't enough, and the game's own teams staying put still count even inside a band.

**Commercial text:** web addresses, APR or MSRP, "see dealer", "closed course", "ask your doctor", 1-800 numbers, two of "shop now" / "lease" / "limited time" and the like, with no score bug in the same read: that box is in a commercial, no waiting. Fine print counts even along the bottom where a ticker runs.

**The score bug's shape** (learned per box) only counts once it has matched the text: present twice during play and gone once in a commercial. If something surer contradicts it, it's relearned.

**The field's green:** every frame it measures how much of each box is football-field green. A wide shot of the field in the last 8 s leans toward the game (never the other way round: close-ups and replays go long stretches without it). It counts on a box once proven there: field with the game 3 times, none in a commercial once.

**Yard lines:** in each close look it searches the green for thin, straight, bright lines that run a good part of the way across the box (yard lines, sidelines, a row of hash marks), leaving out the box's edges and the bottom fifth where the score bar and a ticker run. Two or more long ones in the last 5 s are as strong a sign of the game as its score bug. Lawn stripes (too wide), a golf flag (just one), busy texture like leaves, and lines off the green don't count. A box proves them first: lines with the game twice, none in a commercial once. Lines in a commercial it's sure of start that over.

**The picture, learned.** Besides the signs above, it learns what the game and commercials look like on your TV from the whole picture of each box, about 4 times a second: how often it cuts (ads cut every couple of seconds, a broadcast holds its shots), how much it moves, a strip along the bottom that stays put while play moves (the score bar, whatever its design or logos), black bars top and bottom, brightness, contrast, edges and the field's green, over the last 8 s.
- **It learns only from what's sure:** the score bug read off the screen and your "It's the game" (game); commercial text, your "It's an ad" (that box, for 30 s) and clearly ad-sounding audio with no score bug (commercial). Never from its own guesses, ESPN's timing guesses, or halftime and between-quarter shows. All four boxes teach it at once, so it learns from games you aren't listening to as well.
- **It has to prove itself.** It needs 40 looks of each, then checks itself on every new labeled look before learning from it. It's used only while it's been right on 85%+ of the last 20 of each kind. Diagnostics show "Picture model: learned N game, N ad looks; right on game X%, ads Y%; in use", and the box lines say "looks like a commercial (92%)". What it learned, and its record, survive restarts.
- **What it does:** 90%+ sure it's a commercial rules a box out as a place to go, keeps you from going back to it too early, and makes unsure sound a commercial. 90%+ sure it's the game makes a box a good place to go, marks your game as back, and makes unsure sound the game. It never holds an ad-sounding moment.

**Sound and camera, paired.** Each second, the sound's call on the box you're hearing is paired with what the camera sees there. The camera adds up its evidence: score bug read (strong game), yard lines (strong game), field green (leans game), score bug look (proven) up or gone, commercial text (strong commercial), score bug gone 8-45 s (leans commercial) or 45 s+ (strong commercial). Yard lines or green with the dependable score bug missing count for little: that's a promo or an ad shot on a field.
**The sound decides when to leave, exactly as without the camera.** The camera never holds an ad-sounding moment.
- **The score bug read off the screen right now:** unsure sound counts as the game (so it comes back from a commercial sooner).
- **Camera sure it's a commercial**: an ad-sounding moment is a sure one (beats ESPN's guards). Unsure sound becomes a commercial only with commercial text read there, or the sound already 50%+ ad-like. Game-like sound is a commercial only when commercial text and a long-gone score bug agree and the sound is shaky (30%+).
- **Leaning commercial** (score bug just gone): ad-leaning sound (50%+) is a commercial, ad-sounding is a sure one.
- Your "It's the game" keeps the camera from calling that game a commercial.

The Live screen and your phone page show what the camera sees on each box ("score bug, yard lines", "score bug gone 12 s") and how the last call paired up ("Sound: commercial (72% ad) · Camera: score bug → the game (camera sees it)").

**Other games:** the camera seeing the game there (score bug, yard lines, green, a proven score bar) makes a box a good place to go and marks your game as back. Only commercial text read there rules a box out. The score bug not being read lately just puts that box behind the others; ESPN decides the rest, the way it did before the camera. While ESPN says a game is at halftime or between quarters, only its live score bug read off the screen counts (the band on the field at halftime isn't the game), and that beats ESPN's status, which lags your TV by 30-90 s.

## 6. When it switches (the game you're hearing)

Checked in this order:
1. Switched in the last 6 seconds: wait.
2. Commercial (sound and camera paired, section 5):
   - Bounced twice in 75 seconds (every game in commercial): sit on this one for 45 s, even when it's sure this one is a commercial, but jump as soon as any game is known to be back on (the camera, its clock, a fresh play).
   - ESPN warned of a break: go now (only not in the first 10 s after ESPN posted it).
   - A commercial it's sure of (fingerprint, or the camera backs it up): go now.
   - ESPN guard (play just happened, clock running): stay.
   - Otherwise go.
3. **Where to, in the order you want:**
   - a game being played (sure ones first, then ones it can't vouch for), most exciting first (close, late, red zone, overtime)
   - no game being played anywhere: another game's halftime show
   - nothing but commercials: the one whose break started first (at least 60 s ago)
   - nothing at all: it stays
4. **Halftime on your game:** once your TV reaches it, it goes to a game being played. If none is, it stays for the halftime show.
5. **Parked on a halftime show** because nothing was being played: it leaves for a live game as soon as there is one. A commercial during the show is handled like any other.
6. Game heard: if the game you were watching is back, go back to it.
7. Jumping to a more exciting game mid-play ("hopping"): off.

## 7. Going back to your game

When it leaves your game for a commercial, it remembers it (not the stops along the way). It goes back once it's been at least 15 s since the last switch and:
- the camera reads your game's score bug again (fastest; at halftime or between quarters, only that counts, not the field), or
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

Tabs along the top (v1.0.89):
- **Control:** all of the above.
- **Games:** each box's game; Change searches games on now (or a team or network) and sets it, or sets "something else" by name; fill empty boxes; clear a box.
- **Settings:** mic on or off, how quick to call a commercial, ESPN play-by-play, going back to your game, the camera on or off, typical break length, the layout; what the sound and picture have learned, and buttons to forget either.
- **Camera:** the live picture with the boxes drawn on it; Find the TV again; Move the corners (drag the four dots to the TV's corners, then Save); zoom.
- **TV:** arrows, OK, back, home, volume, mute, play, pause, power. Arrows count toward where the app thinks the sound is.

Both phones need the same Wi-Fi, and the app open on the camera phone. Only a device with the link's key can use it.

## 9. On your TV, these do nothing

- **Open YouTube TV on start** and **pause when YouTube TV isn't on screen:** your TV won't give the app its app list, so these never start ("YouTube TV app: (not found yet)" in diagnostics). The same goes for "On the TV" and the volume readout.
- Roku support, 2- and 3-game layouts, "something else" boxes, hopping, HDMI input teaching: unused by you, and they don't run during games.

## 10. Weak spots for your setup

1. **The camera is untested on your TV.** Distance, glare and each network's bug design decide how much it reads, and the yard-line finder was tuned on drawn pictures, not your TV. Diagnostics show, per box, what it sees and its points, how many yard lines its last look found, whether lines, field and score bug shape are proven, and how many of the app's moves the outline confirmed.
2. **Fields in commercials.** Promos and ads shot on a field have green and lines too, which is why those no longer hold the sound.
3. **ESPN's college play-by-play is late,** often 30-90 seconds and sometimes in bursts. That's why the mic leads and ESPN mostly guards and picks targets. Going back to your game is slow without the camera.
4. **The start position is a guess** (box 1, or where it was). If it's wrong, every switch after it is wrong until you tap "Sound is already on this box," or (once the outline is proven) press an arrow on the TV remote so the camera sees where the sound is.
5. **Your TV's delay** is assumed to be 40 s until it learns it from a few timeouts.
6. **The mic's learning resets** if the app's data gets cleared. A few taps of each button retrains it.

## 11. Reading the Activity log

| Line | Meaning |
|---|---|
| Commercial on box N → box M | Mic heard a commercial, moved to M |
| Recognized a commercial it's heard before on box N → box M | Fingerprint match |
| Sounds like a commercial and the camera agrees (score bug gone 12 s) on box N → box M | Mic and camera agreed |
| Sounded like an ad, but box N just had a play / clock is running. Staying. | An ESPN guard held it |
| Sounded like an ad, but the camera sees the game on box N (score bug, yard lines). Staying. | The camera held it |
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

## 12. Debug pass (v1.0.85)

A full read-through of the logic, with every fix covered by a test that failed before it.

**Fixed:**
1. **Halftime never left for a live game.** ESPN's halftime status and its end-of-half play kept re-arming each other every second, so the "go once your TV gets there" kept getting pushed back. It only left when a commercial sounded. Now it goes to a live game at halftime, or stays for the show if none.
2. **The band at halftime counted as the game.** Yard lines and green from the halftime show made your game look "back" (and the halftime box look "live"). Now, while ESPN says halftime or between quarters, only the live score bug counts, and it counts ahead of ESPN's lagging status.
3. **The halftime show could be called a commercial.** The score bug off during the studio show read as "gone". At halftime that no longer counts; commercial text still does.
4. **A long commercial stopped counting after 2 minutes.** The score bug's read history only went back 2 minutes, so the camera's "commercial" ran out partway through a break. Fixed (introduced in v1.0.83).
5. **Bouncing through four commercials.** Camera-confirmed commercials skipped the "commercials everywhere" pause. They don't now.
6. Coming back to your game could "switch" to the box it was already on; box reads kept going for a few seconds after the phone was turned; Go after opening YouTube TV said box 1 without assuming it.

**Simpler:** the switching logic takes the camera's word from one place (what it sees per box); the score bug's read history is one small tested piece instead of three lists in the engine; the no-live-data "go back to box 1" fallback (which fought where you put the sound) and unused leftovers (athlete-name collection, read strength, old helpers) are gone.

## 13. Camera as helper (v1.0.86)

From the first NFL Sunday with the camera on: it held the sound on commercials and blocked switches because it couldn't read the bugs.

1. **The score bug was thrown out as a ticker.** FOX and CBS bugs sit along the bottom and their clock changes every read, so the "text keeps changing" band swallowed them. A line that's nothing but score bug is never ticker now.
2. **Logo bugs.** With no team names to read, almost nothing counted as the game. A score near ESPN's with down and distance, or a quarter and clock, counts now, after fixing common misreads.
3. **The camera blocked switches.** A box whose bug it couldn't read was called a commercial and ruled out, so "every other game is in commercial, staying." Now only commercial text rules a box out.
4. **The camera held commercials.** Yard lines (found on close-ups) and green held ad-sounding moments. Now only the score bug read right now holds, and not past 80% ad-like sound.
5. **"Score bug gone" lasted 20 minutes.** Capped at 5, and it no longer turns unsure sound into a commercial by itself.
6. **Your "It's an ad" tap always moves you** unless every other box shows commercial text.
7. **The grid slid around** on the minute checks and mixed up which game was where. It needs two looks agreeing now, unless the phone was bumped.

## 14. Faster on the warned break (v1.0.87)

1. **It waited on its own guess.** After ESPN's "break coming" it wouldn't switch until the time it figured the TV would get there, from an assumed 40 s delay. When the TV ran ahead of that, the commercial played and nothing happened. Now it goes the moment the sound turns, and the warning makes it quicker (3 s of commercial sound, about 6 s from the cut).
2. **The camera never holds the sound now.** The score bug stays readable for a few seconds after the cut, so holding on it only made switches late. The camera still speeds switches up, rules out boxes showing commercial text, and spots your game coming back.

## 15. The camera learns the picture (v1.0.88)

The camera's hand-made signs (score bug text, yard lines, green) miss too much on your TV. Now it also learns what the game and commercials look like there, from the whole picture of all four boxes, taught by the sure signs and your taps, and it only counts once it has proven itself (section 5, "The picture, learned").
