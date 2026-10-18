#!/usr/bin/env python3
"""Review a Multiview Audio game recording (the zip from the phone page, or its folder).

    python3 tools/review.py recording.zip [--frames OUTDIR] [--around SECONDS]

Prints: the session, every switch with the sound and camera around it, commercials it seemed to
sit through, slow returns, your taps, feedback and notes with what was going on, and totals.
With --frames, writes contact sheets of the TV pictures around each switch and piece of feedback.
"""
import argparse, io, json, os, sys, zipfile
from datetime import datetime


def load(path):
    files = {}
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                files[n.split('/', 1)[1] if '/' in n else n] = z.read(n)
    else:
        for dp, _, fs in os.walk(path):
            for f in fs:
                full = os.path.join(dp, f)
                files[os.path.relpath(full, path).replace(os.sep, '/')] = open(full, 'rb').read()
    meta = json.loads(files.get('meta.json', b'{}'))
    secs = [json.loads(l) for l in files.get('seconds.jsonl', b'').decode().splitlines() if l.strip()]
    events = [json.loads(l) for l in files.get('events.jsonl', b'').decode().splitlines() if l.strip()]
    frames = sorted((int(k[7:-4]), v) for k, v in files.items() if k.startswith('frames/') and k.endswith('.jpg'))
    return meta, secs, events, frames


def clock(ms):
    return datetime.fromtimestamp(ms / 1000).strftime('%H:%M:%S')


def trace(secs, ms, before, after):
    """The sound around a moment: one character a second (A ad, g game, . unsure), plus ad%."""
    w = [s for s in secs if ms - before * 1000 <= s['ms'] <= ms + after * 1000]
    marks = ''.join({'AD': 'A', 'GAME': 'g'}.get(s['heard'], '.') for s in w if s['ms'] <= ms) + '|' + \
        ''.join({'AD': 'A', 'GAME': 'g'}.get(s['heard'], '.') for s in w if s['ms'] > ms)
    pct = ' '.join(str(s['adPct']) if s['adPct'] >= 0 else '-' for s in w if s['ms'] <= ms)
    return marks, pct, w


def ad_onset(secs, ms, box, look=120):
    """When the sound on [box] started leaning commercial (55%+ ad-like) in the run that ends at the switch [ms]."""
    w = [s for s in secs if ms - look * 1000 <= s['ms'] <= ms]
    while w and w[-1]['box'] != box: w.pop()
    if not w: return None
    onset = None; gap = 0
    for s in reversed(w):
        if s['box'] != box: break
        if s['adPct'] >= 55: onset = s['ms']; gap = 0
        else:
            gap += 1
            if gap > 2: break
    return onset


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('path'); ap.add_argument('--frames'); ap.add_argument('--around', type=int, default=20)
    a = ap.parse_args()
    meta, secs, events, frames = load(a.path)
    if not secs:
        print('No seconds recorded.'); return
    print(f"Session {clock(secs[0]['ms'])}-{clock(secs[-1]['ms'])} ({len(secs) // 60} min), app {meta.get('version', '?')}, layout {meta.get('layout')}")
    for b, g in sorted(meta.get('games', {}).items()):
        print(f"  box {b}: {g.get('name') or g.get('team')}")
    st = meta.get('settings', {})
    print(f"  sensitivity {st.get('micSensitivity')}, ESPN {st.get('proactive')}, camera {st.get('useCamera')}, {len(frames)} pictures")

    switches = [e for e in events if e['kind'] == 'switch']
    feedback = [e for e in events if e['kind'] in ('feedback', 'tap')]
    print(f"\n== {len(switches)} switches ==")
    lags = []
    for e in switches:
        marks, pct, w = trace(secs, e['ms'], a.around, 5)
        onset = ad_onset(secs, e['ms'], e['from'])
        lag = (e['ms'] - onset) / 1000 if onset else None
        if lag is not None and 'marked a commercial' not in e.get('why', ''): lags.append(lag)
        cam = next((s for s in reversed(secs) if s['ms'] <= e['ms']), None)
        here = next((b for b in (cam or {}).get('boxes', []) if b['box'] == e['from']), {})
        print(f"{clock(e['ms'])} box {e['from']} -> {e['to']}: {e.get('why', '')}")
        print(f"   sound (oldest first, | = switch): {marks}\n   ad% before: {pct}")
        if lag is not None: print(f"   sound turned ad-like {lag:.0f} s before the switch")
        if here: print(f"   camera on box {e['from']}: {here.get('sees') or '-'} (pts {here.get('pts')}), look {here.get('look')}, ESPN {here.get('espn')} {here.get('detail')}")
        if cam: print(f"   call: {cam.get('call')}  bias: {cam.get('bias')}")

    print("\n== Sounded like a commercial for 15 s+ without a switch ==")
    run = []
    def flush():
        if len(run) >= 15:
            print(f"{clock(run[0]['ms'])}-{clock(run[-1]['ms'])} box {run[0]['box']} ({len(run)} s), ad% {min(s['adPct'] for s in run)}-{max(s['adPct'] for s in run)}; why: {run[-1].get('why') or '-'}; call: {run[-1].get('call')}")
    for s in secs:
        if s['adPct'] >= 60 and not s.get('paused') and not s.get('locked') and (not run or s['box'] == run[-1]['box']):
            run.append(s)
        else:
            flush(); run = [s] if s['adPct'] >= 60 else []
    flush()

    print("\n== Your taps and feedback ==")
    for e in feedback:
        marks, pct, _ = trace(secs, e['ms'], a.around, 0)
        what = e.get('text') or f"{e.get('what')} box {e.get('box')}"
        print(f"{clock(e['ms'])} {e['kind']}: {what}")
        print(f"   sound before: {marks}   ad%: {pct}")

    print("\n== ESPN plays near switches ==")
    plays = [e for e in events if e['kind'] == 'play']
    for e in switches:
        near = [p for p in plays if abs(p['ms'] - e['ms']) < 120_000 and p['box'] == e['from']]
        for p in near[-3:]:
            print(f"{clock(e['ms'])} box {e['from']}: {p['text'][:90]} (seen {(p['ms'] - e['ms']) / 1000:+.0f} s from the switch)")

    heard_ad = sum(1 for s in secs if s['heard'] == 'AD' and not s.get('paused'))
    print("\n== Totals ==")
    print(f"switches {len(switches)}, seconds heard as commercial {heard_ad} ({100 * heard_ad / max(1, len(secs)):.0f}%)")
    if lags: print(f"sound-to-switch lag: median {sorted(lags)[len(lags) // 2]:.0f} s, worst {max(lags):.0f} s")
    verdicts = [e.get('text', '') for e in events if e['kind'] == 'feedback']
    for k in ('Good switch', 'Too slow', 'Wrong switch', 'Note'):
        n = sum(1 for v in verdicts if v.startswith(k))
        if n: print(f"{k}: {n}")

    if a.frames and frames:
        sheets(a.frames, frames, [(e['ms'], f"switch {e['from']}->{e['to']}") for e in switches] +
               [(e['ms'], e['kind']) for e in feedback], a.around)


def sheets(outdir, frames, moments, around):
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print('(Pillow is needed for --frames)'); return
    os.makedirs(outdir, exist_ok=True)
    for ms, label in moments:
        near = [(t, b) for t, b in frames if ms - around * 1000 <= t <= ms + 10_000]
        if not near: continue
        step = max(1, len(near) // 12); near = near[::step][:12]
        imgs = [Image.open(io.BytesIO(b)).convert('L') for _, b in near]
        w, h = imgs[0].size; cols = 4; rows = (len(imgs) + cols - 1) // cols
        sheet = Image.new('L', (cols * w, rows * (h + 14)), 0); d = ImageDraw.Draw(sheet)
        for i, ((t, _), im) in enumerate(zip(near, imgs)):
            x, y = (i % cols) * w, (i // cols) * (h + 14)
            sheet.paste(im, (x, y + 14)); d.text((x + 3, y + 1), f"{(t - ms) / 1000:+.0f}s", fill=255)
        name = f"{clock(ms).replace(':', '')}_{label.replace(' ', '_').replace('>', '')}.jpg"
        sheet.save(os.path.join(outdir, name), quality=80)
    print(f"\nContact sheets in {outdir}")


if __name__ == '__main__':
    main()
