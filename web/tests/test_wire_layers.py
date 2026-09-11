"""Layer transport preserves the generated RGB and the exact audio tail."""
from pathlib import Path
import sys
import unittest

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from wire import encode_frame, decode_frame, HEADER


def encode_png(rgb):
    pixels = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR) if rgb.ndim == 3 else rgb
    return cv2.imencode(".png", pixels)[1].tobytes()


class Layers(unittest.TestCase):
    def test_face_pixels_and_natural_end_owner_survive_transport(self):
        self.assertEqual(HEADER.size, 44, "Layer media packets need explicit payload boundaries")
        face = np.random.default_rng(17).integers(0, 256, (256, 256, 3), dtype=np.uint8)
        base = np.zeros((32, 32, 3), np.uint8)
        metadata = dict(kind="speech", box=[1, 1, 17, 17], affine=[1, 0, 0, 0, 1, 0],
                        blend=.4, source_bounds=None, end_turn=0)
        encoded = encode_frame(0, 7, 0, 0, base, [.125], generated=True,
                               metadata=metadata, face=encode_png(face), mask=encode_png(np.full((16, 16), 255, np.uint8)))
        decoded = decode_frame(encoded)
        self.assertTrue(np.array_equal(decoded["face"], face))
        self.assertEqual(decoded["metadata"], metadata)
        self.assertEqual(decoded["pcm"][0], 4096)
        self.assertTrue(np.all(decoded["pcm"][1:] == 0))
        metadata.update(kind="end", end_turn=7)
        ended = decode_frame(encode_frame(1, 0, 1, 0, base, [], metadata=metadata))
        self.assertEqual(ended["metadata"]["end_turn"], 7)
        self.assertIsNone(ended["face"])
        metadata["source_bounds"] = [float("nan")] * 4
        with self.assertRaises(ValueError):
            encode_frame(2, 0, 2, 0, base, [], metadata=metadata)


if __name__ == "__main__":
    unittest.main()
