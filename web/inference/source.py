"""Prepare an uploaded video directly into a private, disposable runtime cache."""
from pathlib import Path
import tempfile
import cv2
import numpy as np
from .detection import FaceDetector, align_matrix

DATA = Path(__file__).with_name("data")


class VideoSource:
    def __init__(self, video, cache_directory, face_models, *, max_side=960, clip_seconds=10, progress=None):
        self.frames = self.faces = self._temporary = None
        self.masks, self.geometry = [], []
        self.closed = False
        self.count = 0
        progress = progress or (lambda *args: None)
        Path(cache_directory).mkdir(parents=True, exist_ok=True)
        self._temporary = tempfile.TemporaryDirectory(prefix="video-", dir=cache_directory)
        capture = cv2.VideoCapture(str(video))
        try:
            if not capture.isOpened():
                raise ValueError("视频无法解码，请使用普通 H.264 MP4 视频。")
            source_fps = capture.get(cv2.CAP_PROP_FPS)
            if not np.isfinite(source_fps) or not 1 <= source_fps <= 240:
                raise ValueError("视频帧率无效。")
            self.fps = min(source_fps, 25.)
            expected = min(capture.get(cv2.CAP_PROP_FRAME_COUNT)/source_fps, clip_seconds)*self.fps
            detector = FaceDetector(face_models)
            repair = (DATA / "repair_rgb.raw").read_bytes()
            if len(repair) != 256*256*3:
                raise ValueError("Missing fixed model repair mask")
            self.repair = np.frombuffer(repair, np.uint8).reshape(256, 256, 3)
            yy, xx = np.mgrid[:280, :210]
            distance = np.sqrt(((xx-105)/57)**2 + ((yy-184)/53)**2)
            blend = np.clip((1-distance)/.25, 0, 1)
            blend = (blend*blend*(3-2*blend)*255).astype(np.uint8)
            faces, previous = [], None
            source_index, next_time = 0, 0.
            cache_file = Path(self._temporary.name) / "frames.rgb"
            with cache_file.open("wb") as cache:
                while source_index/source_fps < clip_seconds:
                    ok, bgr = capture.read()
                    if not ok:
                        break
                    timestamp = source_index/source_fps
                    source_index += 1
                    if timestamp + 1e-6 < next_time:
                        continue
                    next_time += 1/self.fps
                    scale = min(1., max_side/max(bgr.shape[:2]))
                    width, height = (max(2, int(v*scale)//2*2) for v in (bgr.shape[1], bgr.shape[0]))
                    if self.count and (width, height) != (self.width, self.height):
                        raise ValueError("视频中途改变尺寸，请使用单一连续片段。")
                    self.width, self.height = width, height
                    bgr = cv2.resize(bgr, (width, height), interpolation=cv2.INTER_AREA)
                    try:
                        anchors = detector.anchors(bgr)
                    except ValueError as error:
                        raise ValueError(f"视频 {timestamp:.2f} 秒处：{error}") from error
                    if previous is not None:
                        step = np.max(np.linalg.norm(anchors-previous, axis=1))
                        if step > max(25., np.linalg.norm(previous[0]-previous[1])*.7):
                            raise ValueError("视频存在切镜或人脸位置突变，请使用一段连续单人镜头。")
                        anchors = .7*anchors + .3*previous
                    previous = anchors
                    matrix = align_matrix(anchors)
                    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                    aligned = cv2.warpAffine(rgb, matrix, (210, 280), borderValue=(127,)*3)
                    faces.append(cv2.resize(aligned, (256, 256), interpolation=cv2.INTER_CUBIC))
                    inverse = cv2.invertAffineTransform(matrix)
                    corners = cv2.transform(np.array([[[0, 0], [210, 0], [0, 280], [210, 280]]], np.float32), inverse)[0]
                    x0, y0 = np.maximum(0, np.floor(corners.min(0))).astype(int)
                    x1, y1 = np.minimum([width, height], np.ceil(corners.max(0))).astype(int)
                    if x1 <= x0 or y1 <= y0:
                        raise ValueError("Face crop is outside the video")
                    local = matrix.copy()
                    local[:, 2] += matrix[:, :2] @ [x0, y0]
                    alpha = cv2.warpAffine(blend, cv2.invertAffineTransform(local), (x1-x0, y1-y0))
                    rgba = np.full((*alpha.shape, 4), 255, np.uint8)
                    rgba[:, :, 3] = alpha
                    ok, encoded = cv2.imencode(".png", rgba)
                    if not ok:
                        raise RuntimeError("Cannot prepare feathered face mask")
                    self.masks.append(encoded.tobytes())
                    self.geometry.append(dict(box=[int(x0), int(y0), int(x1), int(y1)], affine=local.reshape(-1).tolist()))
                    cache.write(rgb.tobytes())
                    self.count += 1
                    if self.count % 5 == 0:
                        progress(min(.85, .85*self.count/max(expected, self.count)), f"正在处理视频：{self.count} 帧")
            if self.count < 2:
                raise ValueError("视频太短，至少需要两帧有效画面。")
            self.frames = np.memmap(cache_file, mode="r", dtype=np.uint8, shape=(self.count, self.height, self.width, 3))
            self._faces_rgb = np.stack(faces)
            self.faces = self._faces_rgb.transpose(0, 3, 1, 2)
            progress(.86, "正在准备播放与模型预热")
        except Exception:
            self.close()
            raise
        finally:
            capture.release()

    def prepare(self):
        pass

    def index(self, phase):
        position = int(int(phase)*self.fps/25) % (self.count*2-2)
        return position if position < self.count else self.count*2-2-position

    def presentation(self, index, kind, blend=0., end_turn=0):
        return dict(self.geometry[index], kind=kind, blend=float(blend), source_bounds=None, end_turn=end_turn)

    def match_color(self, generated, index):
        reference = self._faces_rgb[index, 100:210, 65:190].mean((0, 1))
        actual = generated[100:210, 65:190].mean((0, 1))
        gain = np.clip(reference/np.maximum(actual, 1), .85, 1.15)
        return np.rint(np.clip(generated.astype(np.float32)*gain, 0, 255)).astype(np.uint8)

    def close(self):
        self.closed = True
        mapping = getattr(self.frames, "_mmap", None)
        if mapping is not None and not mapping.closed:
            mapping.close()
        self.frames = self.faces = None
        self.masks.clear()
        self.geometry.clear()
        if self._temporary is not None:
            self._temporary.cleanup()
            self._temporary = None

