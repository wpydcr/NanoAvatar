"""Local SCRFD + 106-point alignment. No downloader or mobile packager."""
from pathlib import Path
import cv2
import numpy as np
import onnxruntime as ort


class FaceDetector:
    def __init__(self, directory):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        self.sessions = []
        for name in ("det_10g.onnx", "2d106det.onnx"):
            path = Path(directory) / name
            if not path.is_file():
                raise FileNotFoundError(f"Missing bundled face model: {name}")
            self.sessions.append(ort.InferenceSession(str(path), sess_options=options,
                                                      providers=["CPUExecutionProvider"]))
        self.centers = {}
        for stride in (8, 16, 32):
            points = np.stack(np.mgrid[:640//stride, :640//stride][::-1], axis=-1)
            self.centers[stride] = np.repeat((points.reshape(-1, 2) * stride).astype(np.float32), 2, axis=0)

    def anchors(self, bgr):
        height, width = bgr.shape[:2]
        scale = 640 / max(height, width)
        rw, rh = max(1, int(width*scale)), max(1, int(height*scale))
        image = np.zeros((640, 640, 3), np.uint8)
        image[:rh, :rw] = cv2.resize(bgr, (rw, rh))
        blob = cv2.dnn.blobFromImage(image, 1/128, (640, 640), (127.5,)*3, swapRB=True)
        detector, landmark = self.sessions
        output = detector.run(None, {detector.get_inputs()[0].name: blob})
        boxes, scores = [], []
        for level, stride in enumerate((8, 16, 32)):
            confidence = output[level].reshape(-1)
            selected = np.flatnonzero(confidence >= .55)
            centers = self.centers[stride][selected]
            distances = output[level+3].reshape(-1, 4)[selected] * stride
            xyxy = np.concatenate((centers-distances[:, :2], centers+distances[:, 2:]), axis=1) / scale
            boxes.extend(xyxy.tolist())
            scores.extend(confidence[selected].tolist())
        if not boxes:
            raise ValueError("未检测到人脸，请使用清晰、单人、正面的视频。")
        rectangles = [[x0, y0, x1-x0, y1-y0] for x0, y0, x1, y1 in boxes]
        keep = np.asarray(cv2.dnn.NMSBoxes(rectangles, scores, .55, .4)).reshape(-1)
        candidates = [np.array(boxes[i], np.float32) for i in keep
                      if min(rectangles[i][2:]) >= 28]
        if len(candidates) != 1:
            raise ValueError("视频中需始终只有一张清晰人脸；当前画面无人脸或有多张人脸。")
        x0, y0, x1, y1 = candidates[0]
        side = max(x1-x0, y1-y0) * 1.5
        factor = 192 / side
        matrix = np.array([[factor, 0, 96-factor*(x0+x1)/2],
                           [0, factor, 96-factor*(y0+y1)/2]], np.float32)
        cropped = cv2.warpAffine(bgr, matrix, (192, 192))
        # This bundled 2d106det graph contains input normalization itself.
        blob = cv2.dnn.blobFromImage(cropped, 1., (192, 192), (0, 0, 0), swapRB=True)
        points = landmark.run(None, {landmark.get_inputs()[0].name: blob})[0].reshape(106, 2)
        points = cv2.transform(((points + 1) * 96)[None], cv2.invertAffineTransform(matrix))[0]
        anchors = np.stack((points[[43, 48, 49, 51, 50]].mean(0),
                            points[101:106].mean(0), points[[74, 77, 83, 86]].mean(0)))
        if not np.isfinite(anchors).all() or np.linalg.norm(anchors[0]-anchors[1]) < 8:
            raise ValueError("人脸过小或角度过大，请换用正面视频。")
        return anchors.astype(np.float32)


def align_matrix(points):
    """Model's eye/nose crop template; independent of any avatar package."""
    target = np.array([[47.6, 56.0], [162.4, 56.0], [105.0, 112.0]], np.float32)
    rows = []
    for x, y in points:
        rows.extend(([x, -y, 1, 0], [y, x, 0, 1]))
    a, b, tx, ty = np.linalg.lstsq(np.asarray(rows), target.reshape(-1), rcond=None)[0]
    if a*a+b*b < 1e-8:
        raise ValueError("Invalid face alignment")
    return np.array([[a, -b, tx], [b, a, ty]], np.float32)

