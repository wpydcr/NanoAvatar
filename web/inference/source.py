"""Native-size avatar-v1 decoding and precomputed mouth geometry."""
import json
from pathlib import Path
import shutil
import tempfile

import cv2
import numpy as np


def child(root, name):
    if not isinstance(name, str) or not name or ":" in name:
        raise ValueError("Invalid person asset path")
    target = (root / name).resolve()
    if not target.is_relative_to(root) or target == root:
        raise ValueError("Person asset path escapes its package")
    if not target.is_file():
        raise ValueError(f"Person package is incomplete: {name}")
    return target


class PhoneSource:
    def __init__(self, directory, cache_directory):
        self.directory = Path(directory).resolve()
        self.cache_directory = Path(cache_directory).resolve()
        self.frames = self.faces = self._raw_faces = self._reference_faces = self._temporary = None
        self.closed = False
        self.masks = []
        self.mouth_bounds = []
        try:
            self.meta = json.loads(child(self.directory, "avatar.json").read_text("utf-8-sig"))
            self.width, self.height = (self.meta[k] for k in ("width", "height"))
            self.count = self.meta["frame_count"]
            self.fps = self.meta["source_fps"]
            if (self.meta.get("version") != 1 or self.fps != 24 or
                    any(type(x) is not int or x <= 0 for x in (self.width, self.height, self.count)) or
                    self.count < 2 or self.width > 8192 or self.height > 8192 or self.count > 2000 or
                    self.meta.get("loop_frames") != self.count * 2 - 2):
                raise ValueError("Unsupported avatar-v1 dimensions or 24 Hz source timeline")
            if len(self.meta["frames"]) != self.count:
                raise ValueError("Person geometry count does not match its video")
            self.video = child(self.directory, self.meta["video"])
            face_path = child(self.directory, "aligned_faces_rgb.bin")
            if face_path.stat().st_size != self.count * 256 * 256 * 3:
                raise ValueError("Aligned face byte count does not match avatar.json")
            self._raw_faces = np.memmap(face_path, dtype=np.uint8, mode="r", shape=(self.count, 256, 256, 3))
            reference_path = child(self.directory, self.meta.get("reference_faces", "aligned_faces_rgb.bin"))
            if reference_path.stat().st_size != face_path.stat().st_size:
                raise ValueError("Reference face byte count does not match avatar.json")
            self._reference_faces = np.memmap(reference_path, dtype=np.uint8, mode="r", shape=(self.count, 256, 256, 3))
            self.faces = self._reference_faces.transpose(0, 3, 1, 2)
            repair = child(self.directory, "repair_rgb.raw").read_bytes()
            if len(repair) != 256 * 256 * 3:
                raise ValueError("Invalid repair image length")
            self.repair = np.frombuffer(repair, np.uint8).reshape(256, 256, 3)
            for frame in self.meta["frames"]:
                box = frame["box"]
                if len(box) != 4 or any(type(x) is not int for x in box):
                    raise ValueError("Invalid mouth rectangle")
                x1, y1, x2, y2 = box
                if not (0 <= x1 < x2 <= self.width and 0 <= y1 < y2 <= self.height):
                    raise ValueError("Mouth rectangle exceeds native frame")
                affine = np.asarray(frame["affine"], np.float32).reshape(2, 3)
                if not np.isfinite(affine).all() or abs(np.linalg.det(affine[:, :2])) < 1e-8:
                    raise ValueError("Invalid mouth transform")
                if (frame["crop_width"], frame["crop_height"]) != (210, 280):
                    raise ValueError("Unsupported alignment crop")
                mask = cv2.imdecode(np.frombuffer(child(self.directory, frame["mask"]).read_bytes(), np.uint8),
                                    cv2.IMREAD_GRAYSCALE)
                if mask is None or mask.shape != (y2 - y1, x2 - x1):
                    raise ValueError("Mouth mask does not match its rectangle")
                self.masks.append(child(self.directory, frame["mask"]).read_bytes())
            self.mouth_bounds = [None if (bounds := mouth_bounds(face)) is None else np.asarray(bounds, np.float32).tolist()
                                 for face in self._raw_faces]
            self.lip_colors = [lip_mean(face) for face in self._raw_faces]
        except Exception:
            self.close()
            raise

    def prepare(self):
        if self.closed:
            raise RuntimeError("Person source is closed")
        if self.frames is not None:
            return
        self.cache_directory.mkdir(parents=True, exist_ok=True)
        needed = self.count * self.height * self.width * 3
        if shutil.disk_usage(self.cache_directory).free < needed + 100 * 1024**2:
            raise OSError(f"Decoded video cache requires {needed} bytes of free disk space")
        self._temporary = tempfile.TemporaryDirectory(prefix="nanoavatar-", dir=self.cache_directory)
        capture = cv2.VideoCapture(str(self.video), cv2.CAP_FFMPEG)
        try:
            if not capture.isOpened():
                raise ValueError("Cannot decode person video")
            actual = (round(capture.get(cv2.CAP_PROP_FRAME_WIDTH)), round(capture.get(cv2.CAP_PROP_FRAME_HEIGHT)))
            if actual != (self.width, self.height) or abs(capture.get(cv2.CAP_PROP_FPS) - 24) > .01:
                raise ValueError("Video metadata does not match the native 24 Hz person package")
            start = self.meta.get("source_start_frame", 0)
            if type(start) is not int or start < 0:
                raise ValueError("Invalid source start frame")
            for _ in range(start):
                if not capture.grab():
                    raise ValueError("Person video ends before its source start")
            self.frames = np.memmap(Path(self._temporary.name) / "video.rgb", dtype=np.uint8, mode="w+",
                                    shape=(self.count, self.height, self.width, 3))
            for index in range(self.count):
                ok, bgr = capture.read()
                if not ok or bgr.shape != (self.height, self.width, 3):
                    raise ValueError(f"Person video is truncated or changes dimensions at frame {index}")
                self.frames[index] = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            self.frames.flush()
        except Exception:
            self.close()
            raise
        finally:
            capture.release()

    def index(self, phase):
        position = (int(phase) * 24 // 25) % (self.count * 2 - 2)
        return position if position < self.count else self.count * 2 - 2 - position

    def frame(self, phase):
        return self.frames[self.index(phase)].copy()

    def presentation(self, index, kind, blend=0.0, end_turn=0):
        frame = self.meta["frames"][index]
        return {"kind": kind, "box": frame["box"], "affine": np.asarray(frame["affine"]).reshape(-1).tolist(),
                "blend": float(blend), "source_bounds": self.mouth_bounds[index], "end_turn": end_turn}

    def match_color(self, generated, index):
        return apply_lip_color(generated, self.lip_colors[index])

    def close(self):
        self.closed = True
        for value in (self.frames, self._raw_faces, self._reference_faces):
            mapping = getattr(value, "_mmap", None)
            if mapping is not None and not mapping.closed:
                mapping.close()
        self.faces = self.frames = self._raw_faces = self._reference_faces = None
        self.masks.clear()
        if self._temporary is not None:
            self._temporary.cleanup()
            self._temporary = None


def color_match(generated, reference):
    """Android LipColorMatch: only chromatic lip pixels, protecting teeth and cavity."""
    return apply_lip_color(generated, lip_mean(reference))


_SRGB = np.arange(256, dtype=np.float64) / 255
_LINEAR = np.where(_SRGB <= .04045, _SRGB / 12.92, ((_SRGB + .055) / 1.055) ** 2.4)
_XYZ = np.arange(16385, dtype=np.float64) / 16384
_LAB_CURVE = np.where(_XYZ > .008856, np.cbrt(_XYZ), 7.787 * _XYZ + 16 / 116)


def smooth(low, high, value):
    t = np.clip((value - low) / (high - low), 0, 1)
    return t * t * (3 - 2 * t)


def lab_curve(value):
    position = np.clip(value * 16384, 0, 16384)
    index = np.minimum(position.astype(np.int32), 16383)
    return _LAB_CURVE[index] + (_LAB_CURVE[index + 1] - _LAB_CURVE[index]) * (position - index)


def lip_lab(rgb):
    region = rgb[132:186, 82:174]
    r, g, b = np.moveaxis(_LINEAR[region], -1, 0)
    fy = lab_curve(.212671 * r + .715160 * g + .072169 * b)
    fx = lab_curve((.412453 * r + .357580 * g + .180423 * b) / .950456)
    a = np.clip(np.rint(500 * (fx - fy)) + 128, 0, 255).astype(np.uint8)
    return region, a, (116 * fy - 16) * 2.55


def lip_mean(rgb):
    region, a, light = lip_lab(rgb)
    rank = int(np.ceil(.85 * (a.size - 1)))
    threshold = np.partition(a.reshape(-1), rank)[rank]
    chosen = region[(a >= threshold) & (light > 35)]
    if len(chosen) < 100:
        return None
    mean = chosen.mean(axis=0)
    return mean if np.all(mean > 0) else None


def apply_lip_color(generated, target):
    rgb = generated.copy()
    current = lip_mean(rgb)
    if target is None or current is None:
        return rgb
    region = rgb[132:186, 82:174].astype(np.float64)
    r, g, b = np.moveaxis(region, -1, 0)
    total = r + g + b
    score = np.maximum(0, 255 * (r - np.maximum(g, b)) / (total + 1))
    yy, xx = np.mgrid[132:186, 82:174]
    spatial = (1 - smooth(.85, 1, np.hypot((xx - 128) / 46, (yy - 159) / 27))).astype(np.float32)
    weight = spatial * smooth(25.5, 56.1, score) * smooth(96, 160, total)
    ratio = np.clip(target / current, .8, 1.2) - 1
    rgb[132:186, 82:174] = np.rint(np.clip(region * (1 + weight[..., None] * ratio), 0, 255)).astype(np.uint8)
    return rgb


def mouth_bounds(rgb):
    """Android MouthMotion source anchors; invalid low-chroma mouths return null."""
    _, a, _ = lip_lab(rgb)
    median = np.partition(a.reshape(-1), a.size // 2 - 1)[a.size // 2 - 1]
    weights = np.maximum(0, a.astype(np.float64) - int(median) - 8)
    if weights.sum() < 500:
        return None

    def quantile(values, fraction, origin):
        cumulative = np.cumsum(values)
        rank = fraction * cumulative[-1]
        index = int(np.searchsorted(cumulative, rank))
        before = cumulative[index - 1] if index else 0
        return float(origin + index - .5 + (rank - before) / values[index])

    columns, rows = weights.sum(axis=0), weights.sum(axis=1)
    return [quantile(columns, .05, 82), quantile(columns, .95, 82),
            quantile(rows, .05, 132), quantile(rows, .95, 132)]
