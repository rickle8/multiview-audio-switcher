"""Listens through a microphone and decides: game audio or commercial?

Uses Google's YAMNet audio classifier (521 sound classes). Game broadcasts are
heavy on crowd/cheering/babble; commercials are heavy on music and clean
voiceover. We keep a rolling window of scores and apply hysteresis so a single
quiet moment or a song in the stadium doesn't flip the verdict.
"""
from __future__ import annotations

import csv
import logging
import queue
import time
from collections import deque
from dataclasses import dataclass

import numpy as np

log = logging.getLogger(__name__)

SAMPLE_RATE = 16000
CHUNK_SECONDS = 1.0

GAME_CLASSES = [
    "Crowd", "Cheering", "Applause", "Hubbub, speech noise, speech babble",
    "Chatter", "Whistle", "Roar",
]
AD_CLASSES = [
    "Music", "Pop music", "Hip hop music", "Rock music", "Electronic music",
    "Jingle (music)", "Theme music", "Background music", "Soundtrack music",
    "Television", "Singing",
]

GAME, AD, UNSURE = "game", "ad", "unsure"


@dataclass
class Frame:
    t: float
    game: float
    ad: float
    loud: float  # RMS in dBFS


class Verdict:
    """Rolling window + hysteresis over per-second scores."""

    def __init__(self, window_secs: float = 8, ad_threshold: float = 0.6,
                 game_threshold: float = 0.4, min_ad_secs: float = 5,
                 silence_dbfs: float = -55):
        self.window_secs = window_secs
        self.ad_threshold = ad_threshold
        self.game_threshold = game_threshold
        self.min_ad_secs = min_ad_secs
        self.silence_dbfs = silence_dbfs
        self.frames: deque[Frame] = deque()
        self.state = UNSURE
        self.ad_since: float | None = None

    def reset(self) -> None:
        self.frames.clear()
        self.state = UNSURE
        self.ad_since = None

    def ad_fraction(self) -> float | None:
        """Share of 'ad-ness' in the window, 0..1. None if not enough signal."""
        useful = [f for f in self.frames if f.loud > self.silence_dbfs]
        if len(useful) < max(3, len(self.frames) // 2):
            return None
        game = sum(f.game for f in useful)
        ad = sum(f.ad for f in useful)
        if game + ad < 0.05 * len(useful):
            return None  # nothing recognizable (talking in the room, silence)
        return ad / (game + ad)

    def add(self, frame: Frame) -> str:
        self.frames.append(frame)
        while self.frames and frame.t - self.frames[0].t > self.window_secs:
            self.frames.popleft()
        if self.frames[-1].t - self.frames[0].t < self.window_secs * 0.6:
            return self.state  # window still filling

        frac = self.ad_fraction()
        if frac is None:
            return self.state
        if frac >= self.ad_threshold:
            if self.ad_since is None:
                self.ad_since = frame.t
            if frame.t - self.ad_since >= self.min_ad_secs:
                self.state = AD
        elif frac <= self.game_threshold:
            self.ad_since = None
            self.state = GAME
        else:
            self.ad_since = None
        return self.state


class YamnetScorer:
    def __init__(self):
        import tensorflow_hub as hub

        log.info("Loading YAMNet model (first run downloads ~15 MB)...")
        self.model = hub.load("https://tfhub.dev/google/yamnet/1")
        names = []
        with open(self.model.class_map_path().numpy().decode()) as f:
            for row in csv.DictReader(f):
                names.append(row["display_name"])
        self.names = names
        self.game_idx = [names.index(n) for n in GAME_CLASSES if n in names]
        self.ad_idx = [names.index(n) for n in AD_CLASSES if n in names]

    def score(self, wav: np.ndarray) -> tuple[float, float, list[tuple[str, float]]]:
        scores, _, _ = self.model(wav.astype(np.float32))
        mean = scores.numpy().mean(axis=0)
        game = float(mean[self.game_idx].sum())
        ad = float(mean[self.ad_idx].sum())
        top = [(self.names[i], float(mean[i])) for i in mean.argsort()[-3:][::-1]]
        return game, ad, top


class MicListener:
    """Yields one Frame per second of microphone audio."""

    def __init__(self, device=None, scorer: YamnetScorer | None = None):
        self.device = device
        self.scorer = scorer or YamnetScorer()
        self.q: queue.Queue = queue.Queue()
        self.stream = None
        self.last_top: list[tuple[str, float]] = []

    def start(self) -> None:
        import sounddevice as sd

        block = int(SAMPLE_RATE * CHUNK_SECONDS)
        self.stream = sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                                     blocksize=block, device=self.device,
                                     callback=lambda data, *_: self.q.put(data[:, 0].copy()))
        self.stream.start()

    def drain(self) -> None:
        """Throw away buffered audio (used right after switching games)."""
        while not self.q.empty():
            self.q.get_nowait()

    def next_frame(self, timeout: float = 3.0) -> Frame | None:
        try:
            wav = self.q.get(timeout=timeout)
        except queue.Empty:
            return None
        rms = float(np.sqrt(np.mean(wav ** 2)) + 1e-9)
        game, ad, self.last_top = self.scorer.score(wav)
        return Frame(t=time.monotonic(), game=game, ad=ad, loud=20 * np.log10(rms))
