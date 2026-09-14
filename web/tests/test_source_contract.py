"""Direct-video cache, failure cleanup and alpha-mask regressions."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import sys
import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from inference.source import VideoSource
from inference.detection import align_matrix


class VideoTests(unittest.TestCase):
    def make_video(self, path):
        writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"MJPG"), 12, (96, 128))
        self.assertTrue(writer.isOpened())
        for value in (60, 100, 140):
            writer.write(np.full((128, 96, 3), value, np.uint8))
        writer.release()

    def test_video_needs_no_person_package_and_keeps_source_fps(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_video(root/"person.avi")
            points = np.array([[27, 36], [66, 36], [47, 60]], np.float32)
            with patch("inference.source.FaceDetector") as detector:
                detector.return_value.anchors.return_value = points
                source = VideoSource(root/"person.avi", root/"cache", root/"unused")
            try:
                self.assertEqual((source.width, source.height, source.count, source.fps), (96, 128, 3, 12.))
                self.assertEqual(source.faces.shape, (3, 3, 256, 256))
                self.assertEqual(source.index(0), 0)
                self.assertEqual(source.index(5), 2)
                mask = cv2.imdecode(np.frombuffer(source.masks[0], np.uint8), cv2.IMREAD_UNCHANGED)
                self.assertEqual(mask.shape[2], 4)
                self.assertEqual(mask[:, :, 3].min(), 0)
                self.assertGreater(mask[:, :, 3].max(), 200)
                self.assertIsNone(source.presentation(0, "speech")["source_bounds"])
                self.assertFalse(list(root.rglob("avatar.json")))
            finally:
                source.close()
            self.assertEqual(list((root/"cache").iterdir()), [])

    def test_bad_video_or_missing_face_releases_partial_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_video(root/"person.avi")
            with patch("inference.source.FaceDetector") as detector:
                detector.return_value.anchors.side_effect = ValueError("no face")
                with self.assertRaisesRegex(ValueError, "no face"):
                    VideoSource(root/"person.avi", root/"cache", root/"unused")
            self.assertEqual(list((root/"cache").iterdir()), [])

    def test_alignment_maps_the_trained_template(self):
        target = np.array([[47.6, 56.], [162.4, 56.], [105., 112.]], np.float32)
        input_points = target*.5 + [13., 21.]
        actual = cv2.transform(input_points.astype(np.float32)[None], align_matrix(input_points))[0]
        np.testing.assert_allclose(actual, target, atol=1e-4)


if __name__ == "__main__":
    unittest.main()

