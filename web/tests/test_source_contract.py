"""CPU checks for distinct model references and visible avatar assets."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import cv2
import numpy as np

spec = importlib.util.spec_from_file_location("avatar_source", Path(__file__).resolve().parents[1] / "inference/source.py")
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)


class SourceContract(unittest.TestCase):
    def test_color_match_preserves_skin_teeth_and_cavity(self):
        generated = np.full((256, 256, 3), [150, 110, 90], np.uint8)
        reference = generated.copy()
        generated[145:172, 100:156] = [170, 45, 65]
        reference[145:172, 100:156] = [200, 65, 75]
        generated[155:160, 112:140] = [230, 230, 230]
        generated[162:168, 112:140] = [12, 10, 10]
        reference[92:125, 72:184] = [190, 150, 130]
        corrected = source.color_match(generated, reference)
        self.assertTrue(np.array_equal(corrected[:132], generated[:132]), "Lip correction must not recolor the whole face")
        self.assertTrue(np.array_equal(corrected[155:160, 112:140], generated[155:160, 112:140]))
        self.assertTrue(np.array_equal(corrected[162:168, 112:140], generated[162:168, 112:140]))
        self.assertGreater(int(corrected[150, 120, 0]), int(generated[150, 120, 0]))

    def test_display_mouth_is_never_fed_back_as_model_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "video.mp4").touch()
            np.full((2, 256, 256, 3), 30, np.uint8).tofile(root / "aligned_faces_rgb.bin")
            np.full((2, 256, 256, 3), 180, np.uint8).tofile(root / "original.bin")
            np.zeros((256, 256, 3), np.uint8).tofile(root / "repair_rgb.raw")
            cv2.imwrite(str(root / "mask.png"), np.full((16, 16), 255, np.uint8))
            frame = dict(box=[0, 0, 16, 16], affine=[1, 0, 0, 0, 1, 0], crop_width=210, crop_height=280, mask="mask.png")
            meta = dict(version=1, width=16, height=16, frame_count=2, source_fps=24,
                        loop_frames=2, video="video.mp4", reference_faces="original.bin", frames=[frame, frame])
            (root / "avatar.json").write_text(json.dumps(meta))
            person = source.PhoneSource(root, root / "cache")
            try:
                self.assertTrue(np.all(person.faces == 180), "Display pixels must not enter the face encoder")
                self.assertTrue(np.all(person._raw_faces == 30), "Display pixels still own color/geometry targets")
            finally:
                person.close()


if __name__ == "__main__":
    unittest.main()
