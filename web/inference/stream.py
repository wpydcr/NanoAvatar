"""Bounded, sample-clocked streaming independent of transport packet boundaries.

HuBERT is evaluated on overlapping finite contexts, not made causal or retrained.
Exhaust each push/finish iterator before calling another one. Pulling frames is
the backpressure mechanism; cancel() may interrupt an active iterator.
"""
from dataclasses import dataclass
from fractions import Fraction
import math
import time

import numpy as np


@dataclass(frozen=True)
class StreamConfig:
    sample_rate: int = 16000
    fps: int = 25
    block_frames: int = 10
    left_context_ms: int = 1000
    right_context_ms: int = 400
    reset_frames: int = 10

    def __post_init__(self):
        if self.sample_rate != 16000 or self.fps != 25:
            raise ValueError("This checkpoint adapter uses 16 kHz PCM and 25 FPS")
        if not 1 <= self.block_frames <= 10 or self.reset_frames != 10:
            raise ValueError("block_frames must be 1..10; retain checkpoint reset_frames=10")
        if self.left_context_ms < 0 or self.left_context_ms % 20:
            raise ValueError("left context must be a nonnegative multiple of 20 ms")
        if self.right_context_ms < 180 or self.right_context_ms % 20:
            raise ValueError("right context must be >=180 ms and a multiple of 20 ms")
        if self.left_samples + self.block_samples + self.right_samples > 28800:
            raise ValueError("HuBERT supports at most 28800 samples of combined context and block")

    @property
    def hop(self):
        return self.sample_rate // self.fps

    @property
    def block_samples(self):
        return self.block_frames * self.hop

    @property
    def left_samples(self):
        return self.left_context_ms * 16

    @property
    def right_samples(self):
        return self.right_context_ms * 16


@dataclass
class FramePacket:
    index: int
    pts: Fraction
    rgb: np.ndarray
    face_rgb: np.ndarray
    audio: np.ndarray
    input_samples_received: int
    generation_seconds: float


class StreamingSession:
    """One utterance. Models are shared; PCM buffers and LSTM state are private.

backend.extract(pcm) -> [steps, 1024] features;
backend.new_state() and backend.render(feature, state) -> rgb, face_rgb, state.
No backend is given samples beyond the advertised, fixed context boundary.
"""
    def __init__(self, backend, config=None):
        self.backend = backend
        self.config = config or StreamConfig()
        self.status = "open"
        self._busy = False
        self._pcm = np.empty(0, np.float32)
        self._base = 0
        self.received_samples = 0
        self.next_frame = 0
        self._state = backend.new_state()
        backend._acquire_session(self)
        self.stats = {"feature_calls": 0, "feature_seconds": 0.0,
                      "render_seconds": 0.0, "max_buffer_samples": 0,
                      "first_frame_input_samples": None, "first_frame_ms": None,
                      "first_frame_definition": "feature_extraction_start_to_first_generated_rgb"}

    @property
    def buffer_samples(self):
        return len(self._pcm)

    def push_audio(self, samples):
        """Yield as soon as a fixed context is available, even before EOF.

        Input: mono float32 PCM in [-1, 1]. Irregular and empty chunks are valid.
        Large chunks are ingested in small pieces so internal storage stays bounded.
        """
        if self.status != "open" or self._busy:
            raise RuntimeError("Session closed/cancelled or previous iterator not exhausted")
        samples = np.asarray(samples)
        if samples.dtype != np.float32 or samples.ndim != 1:
            raise ValueError("Expected one-dimensional float32 mono PCM at 16000 Hz")
        if not np.isfinite(samples).all() or (samples.size and np.max(np.abs(samples)) > 1.001):
            raise ValueError("PCM must contain finite samples in [-1, 1]")
        return self._push(samples)

    def _push(self, samples):
        if self._busy or self.status != "open":
            raise RuntimeError("Session cannot accept another active input iterator")
        self._busy = True
        try:
            for offset in range(0, len(samples), self.config.block_samples):
                if self.status != "open":
                    return
                piece = samples[offset:offset + self.config.block_samples]
                self._pcm = np.concatenate((self._pcm, piece))
                self.received_samples += len(piece)
                self.stats["max_buffer_samples"] = max(self.stats["max_buffer_samples"], len(self._pcm))
                while (self.status == "open" and self.received_samples >=
                       self.next_frame * self.config.hop + self.config.block_samples + self.config.right_samples):
                    yield from self._block(self.config.block_frames, final=False)
        except Exception:
            self.cancel()
            raise
        finally:
            self._busy = False

    def finish(self):
        """Signal EOF; drain a short final block without dropping tail audio."""
        if self._busy:
            raise RuntimeError("Exhaust the preceding iterator before finish")
        if self.status == "cancelled":
            raise RuntimeError("Session cancelled")
        return self._finish()

    def _finish(self):
        if self.status == "finished":
            return
        if self._busy or self.status != "open":
            raise RuntimeError("Session cannot finish in its current state")
        self._busy = True
        try:
            target = math.ceil(self.received_samples / self.config.hop)
            while self.next_frame < target and self.status == "open":
                yield from self._block(min(self.config.block_frames, target - self.next_frame), final=True)
            if self.status == "open":
                self.status = "finished"
                self.backend._release_session(self)
            self._pcm = np.empty(0, np.float32)
            self._state = None
        except Exception:
            self.cancel()
            raise
        finally:
            self._busy = False

    def _block(self, count, final):
        cfg = self.config
        if self.next_frame == 0:
            self.backend.begin_speech()
        start_frame = self.next_frame
        start = start_frame * cfg.hop
        left = max(0, start - cfg.left_samples)
        right = min(self.received_samples, start + cfg.block_samples + cfg.right_samples)
        pcm = self._pcm[left - self._base:right - self._base].copy()
        if pcm.size < 400:
            pcm = np.pad(pcm, (0, 400 - pcm.size))
        before = time.perf_counter()
        extract = getattr(self.backend, "stream_extract", self.backend.extract)
        features = extract(pcm)
        self.stats["feature_seconds"] += time.perf_counter() - before
        self.stats["feature_calls"] += 1
        if features.ndim != 2 or features.shape[1] != 1024 or len(features) < 1:
            raise ValueError("Feature backend must return nonempty [steps, 1024]")
        if len(features) < 10:
            if hasattr(features, "new_zeros"):
                padded = features.new_zeros((10, features.shape[1]))
                padded[:len(features)] = features
                features = padded
            else:
                features = np.pad(features, ((0, 10 - len(features)), (0, 0)))
        windows = []
        for index in range(start_frame, start_frame + count):
            feature_start = (index * cfg.hop - left) // 320
            if feature_start + 10 > len(features):
                if not final:
                    raise RuntimeError("Insufficient right context for an unfinalized block")
                feature_start = len(features) - 10
            windows.append(features[feature_start:feature_start + 10])
        batch = None
        batch_start = 0
        batch_seconds = 0.0
        batched = callable(getattr(self.backend, "render_batch", None))
        for offset, (index, window) in enumerate(zip(range(start_frame, start_frame + count), windows)):
            if self.status != "open":
                return
            if batched:
                if batch is None or offset >= batch_start + len(batch):
                    # Preserve the original HuBERT window and LSTM order. Only
                    # split independent image work so the first packet can be
                    # consumed before the remaining images are rendered.
                    batch_start = offset
                    size = 1 if index == 0 else count - offset
                    before = time.perf_counter()
                    batch, self._state = self.backend.render_batch(
                        windows[offset:offset + size], self._state, index, cfg.reset_frames)
                    batch_seconds = time.perf_counter() - before
                    if len(batch) != size:
                        raise ValueError("Batch renderer returned a different frame count")
                rgb, face = batch[offset - batch_start]
                elapsed = batch_seconds / len(batch)
            else:
                if index % cfg.reset_frames == 0:
                    self._state = self.backend.new_state()
                before = time.perf_counter()
                rgb, face, self._state = self.backend.render(window, self._state)
                elapsed = time.perf_counter() - before
            self.stats["render_seconds"] += elapsed
            if self.stats["first_frame_ms"] is None:
                begin = getattr(self.backend, "first_feature_started_at", None)
                completed = getattr(self.backend, "first_rgb_completed_at", None)
                if begin is not None and completed is not None:
                    self.stats["first_frame_ms"] = (completed - begin) * 1000
            audio_start = index * cfg.hop
            audio_end = min(audio_start + cfg.hop, self.received_samples)
            audio = self._pcm[audio_start - self._base:audio_end - self._base].copy()
            self.next_frame = index + 1
            if self.stats["first_frame_input_samples"] is None:
                self.stats["first_frame_input_samples"] = self.received_samples
            yield FramePacket(index, Fraction(index, cfg.fps), rgb, face, audio,
                              self.received_samples, elapsed)
        keep_from = max(0, self.next_frame * cfg.hop - cfg.left_samples)
        remove = max(0, min(len(self._pcm), keep_from - self._base))
        self._pcm = self._pcm[remove:].copy()
        self._base += remove

    def cancel(self):
        if self.status in ("cancelled", "finished"):
            return
        self.status = "cancelled"
        self._pcm = np.empty(0, np.float32)
        self._state = None
        self.backend._release_session(self)

    def close(self):
        if self.status == "open":
            self.cancel()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
