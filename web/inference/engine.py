"""Serialized floating/native integer CUDA engine with independent utterance state."""
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import threading
import time

import cv2
import numpy as np
import torch

from .models import Models
from .source import PhoneSource

class AvatarEngine:
    def __init__(self, directory, model_directory, cache_directory=None):
        self._thread = threading.get_ident()
        self.closed = False
        self.models = self.source = None
        self._owner = None
        self.cache = OrderedDict()
        self.cache_limit, self.cache_dtype, self.stride = 10, np.dtype("float16"), 1
        self.phase = self.speech_frames = self.generated_frames = 0
        self.first_rgb_completed_at = None
        self.first_feature_started_at = None
        self._speech_open = False
        self._idle_jpegs = []
        self._last_idle_phase = -1
        self._last_idle_jpeg = None
        self._warmed = False
        try:
            cv2.setNumThreads(1)
            self.models = Models(model_directory)
            self.source = PhoneSource(directory, cache_directory or Path.cwd() / "outputs/nanoavatar-cache")
            self.width, self.height = self.source.width, self.source.height
            self.source.prepare()
            self.mask = np.ascontiguousarray(self.source.repair.transpose(2, 0, 1).astype(np.float32) / 255)
        except Exception:
            self.close()
            raise

    def _check(self):
        if self.closed:
            raise RuntimeError("NanoAvatar engine is closed")
        if threading.get_ident() != self._thread:
            raise RuntimeError("Load, warm up, invoke and close this engine on the same worker thread")

    def _run(self, name, inputs):
        self._check()
        try:
            return self.models.run(name, inputs)
        except Exception:
            self.close()
            raise

    def _acquire_session(self, session):
        self._check()
        if self._owner is not None:
            raise RuntimeError("Finish or cancel the existing StreamingSession before starting another")
        self._owner = session

    def _release_session(self, session):
        if self._owner is session:
            self._owner = None
            self.end_speech()

    def new_state(self):
        return (torch.zeros((2, 1, 576), device="cuda:0"),
                torch.zeros((2, 1, 576), device="cuda:0"))

    def stream_extract(self, pcm):
        values = np.asarray(pcm, np.float32)
        if values.ndim != 1 or not 400 <= len(values) <= 28800 or not np.isfinite(values).all():
            raise ValueError("HuBERT expects a finite 400..28800-sample PCM window")
        if self._speech_open and self.first_feature_started_at is None:
            self.first_feature_started_at = time.perf_counter()
        normalized = (values - values.mean()) / np.sqrt(values.var() + 1e-7)
        tensor = torch.from_numpy(normalized[None]).to(device="cuda:0", dtype=torch.float16)
        return self._run("hubert", {"pcm": tensor})

    def extract(self, pcm):
        result = self.stream_extract(pcm).cpu().numpy()
        if not np.isfinite(result).all():
            self.close()
            from .models import CUDAExecutionError
            raise CUDAExecutionError("HuBERT produced nonfinite features")
        return result

    def cache_references(self, indices):
        missing = list(dict.fromkeys(index for index in indices if index not in self.cache))
        if missing:
            faces = np.stack([self.source.faces[index] for index in missing]).astype(np.float32) / 255
            masks = np.broadcast_to(self.mask, faces.shape)
            images = np.concatenate((masks[:, :1], faces * masks, faces), axis=1)
            skips = self._run("face", {"image": torch.from_numpy(images).to("cuda:0")})
            # Reference storage uses host FP16; the face encoder and generator remain FP32.
            cached = [value.half().cpu().numpy() for value in skips]
            for row, index in enumerate(missing):
                self.cache[index] = [np.ascontiguousarray(value[row:row + 1]) for value in cached]
        for index in indices:
            self.cache.move_to_end(index)

    @torch.inference_mode()
    def render_batch(self, windows, state, start_frame, reset_frames=10):
        self._check()
        count = len(windows)
        if not 1 <= count <= 10 or reset_frames != 10:
            raise ValueError("Render at most ten 25 Hz steps and retain the ten-step LSTM reset")
        audio = torch.stack([window if isinstance(window, torch.Tensor) else
                             torch.from_numpy(np.ascontiguousarray(window)) for window in windows]).to(
                                 device="cuda:0", dtype=torch.float32)
        h, c = state
        features, h, c = self._run("audio", {"windows": audio, "h": h, "c": c,
                                              "start_frame": start_frame})
        indices = [self.source.index(self.phase + i) for i in range(count)]
        self.cache_references(indices)
        skips = [np.concatenate([self.cache[index][level] for index in indices]).astype(np.float32)
                 for level in range(7)]
        tensors = [features] + [torch.from_numpy(value).to("cuda:0") for value in reversed(skips)]
        predicted = self._run("generator", {"tensors": tensors})
        valid = torch.isfinite(predicted).all() & torch.isfinite(audio).all() & torch.isfinite(h).all() & torch.isfinite(c).all()
        pixels = (predicted.permute(0, 2, 3, 1).clamp(0, 1) * 255).round().byte()
        packed = torch.cat((valid.to(torch.uint8).reshape(1), pixels.reshape(-1))).cpu().numpy()
        if not packed[0]:
            self.close()
            from .models import CUDAExecutionError
            raise CUDAExecutionError("CUDA produced nonfinite features or pixels")
        faces = []
        for index, face in zip(indices, packed[1:].reshape(tuple(pixels.shape))):
            faces.append(self.source.match_color(face, index))
            if self.first_rgb_completed_at is None:
                self.first_rgb_completed_at = time.perf_counter()
        # The browser owns visible opacity, mouth motion and release. Sending
        # uncovered source frames lets cancellation discard a mouth immediately.
        full = [self.source.frames[index].copy() for index in indices]
        self.speech_frames += count
        self.generated_frames += count
        self.phase += count
        while len(self.cache) > self.cache_limit:
            self.cache.popitem(last=False)
        return list(zip(full, faces)), (h, c)

    def begin_speech(self):
        self._check()
        if self._speech_open:
            return
        self._speech_open = True
        self.speech_frames = self.generated_frames = 0
        self.first_rgb_completed_at = None
        self.first_feature_started_at = None

    def end_speech(self):
        if self.closed or not self._speech_open:
            return
        self._check()
        self._speech_open = False

    def idle(self):
        self._check()
        phase = self.phase
        self._last_idle_phase, self._last_idle_jpeg = phase, None
        index = self.source.index(phase)
        rgb = self.source.frames[index].copy()
        if self._idle_jpegs:
            self._last_idle_jpeg = self._idle_jpegs[index]
        self.phase += 1
        return phase, rgb

    def idle_jpeg(self, phase):
        self._check()
        return self._last_idle_jpeg if phase == self._last_idle_phase else None

    def _encode_idle(self, index):
        rgb = self.source.frames[index].copy()
        ok, encoded = cv2.imencode(".jpg", cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR),
                                  [cv2.IMWRITE_JPEG_QUALITY, 90, cv2.IMWRITE_JPEG_SAMPLING_FACTOR,
                                   cv2.IMWRITE_JPEG_SAMPLING_FACTOR_444])
        if not ok:
            raise RuntimeError("Cannot encode idle video")
        return encoded.tobytes()

    def warmup(self):
        self._check()
        if self._warmed:
            return
        if self._owner is not None:
            raise RuntimeError("Warm up before starting a StreamingSession")
        from .stream import StreamingSession
        phase = self.phase
        with StreamingSession(self) as session:
            for _ in session.push_audio(np.zeros(19200, np.float32)):
                pass
        self.phase = phase
        self.first_rgb_completed_at = None
        self.first_feature_started_at = None
        self.speech_frames = self.generated_frames = 0
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="nanoavatar-idle-jpeg") as encoder:
            self._idle_jpegs = list(encoder.map(self._encode_idle, range(self.source.count)))
        torch.cuda.synchronize()
        torch.cuda.empty_cache()
        self._warmed = True

    def runtime_info(self):
        self._check()
        quantized = self.models.quantized
        return {"device": "GPU", "providers": ["PyTorch CUDA", "native CUDA INT8"] if quantized else ["PyTorch CUDA"],
                "precision": "HuBERT W8A16 integer + lip mixed INT8" if quantized else "HuBERT FP16 + lip FP32",
                "model_precision": {"hubert": "W8A16 integer", "lip": "mixed INT8 / FP32"} if quantized else {"hubert": "FP16", "lip": "FP32"},
                "reference_cache_precision": "FP16", "automatic_fallback": False,
                "gpu": torch.cuda.get_device_name(0), "torch_version": torch.__version__,
                "load_timings_ms": dict(self.models.load_timings)}

    def gpu_memory(self, *, reset_peak=False):
        """CUDA tensor allocation for both resident models; call in an isolated process."""
        self._check()
        return self.models.memory(reset_peak=reset_peak)

    @torch.inference_mode()
    def benchmark_core(self, *, frames=512):
        """Historical comparison: all lip stages on GPU, excluding HuBERT and pixel readback."""
        self._check()
        if self._owner is not None or not 1 <= frames <= 10000:
            raise ValueError("Run diagnostics between sessions, with 1..10000 frames")
        feature = self.stream_extract(np.zeros(16000, np.float32))[:10].unsqueeze(0)
        face = self.source.faces[self.source.index(self.phase)].astype(np.float32)[None] / 255
        mask = np.broadcast_to(self.mask, face.shape)
        image = torch.from_numpy(np.concatenate((mask[:, :1], face * mask, face), axis=1)).to("cuda:0")
        h, c = self.new_state()
        for _ in range(32):
            output, h, c = self.models.lip(feature, image, h, c)
        torch.cuda.synchronize()
        begin, end = torch.cuda.Event(enable_timing=True), torch.cuda.Event(enable_timing=True)
        begin.record()
        for _ in range(frames):
            output, h, c = self.models.lip(feature, image, h, c)
        end.record()
        end.synchronize()
        seconds = begin.elapsed_time(end) / 1000
        return {"frames": frames, "seconds": seconds, "fps": frames / seconds,
                "scope": "lip audio encoder + face encoder + generator; no HuBERT, readback or composition"}

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self._owner is not None:
            self._owner.cancel()
        self.cache.clear()
        self._idle_jpegs.clear()
        self._last_idle_jpeg = None
        if self.models is not None:
            self.models.close()
        if self.source is not None:
            self.source.close()

    def __enter__(self):
        self._check()
        return self

    def __exit__(self, *_):
        self.close()


def load_avatar(directory, *, models, cache_directory=None):
    """Load an avatar-v1 person with a complete floating or native integer model pair."""
    return AvatarEngine(directory, models, cache_directory=cache_directory)
