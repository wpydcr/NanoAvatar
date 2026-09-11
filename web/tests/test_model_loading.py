"""CPU checks for model selection and rejection of incompatible checkpoints."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import torch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from inference import models


class ModelLoading(unittest.TestCase):
    def test_complete_pairs_only_and_floating_pair_keeps_priority(self):
        select = getattr(models, "select_model_files", None)
        self.assertTrue(callable(select), "The Web loader must recognize quantized model pairs")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(FileNotFoundError):
                select(root)
            (root / "hubert_w8a16.pt").touch()
            (root / "lip_fp32.pt").touch()
            with self.assertRaises(FileNotFoundError):
                select(root)
            (root / "lip_mixed_int8.pt").touch()
            self.assertEqual(select(root), ("hubert_w8a16.pt", "lip_mixed_int8.pt"))
            (root / "hubert_fp16.pt").touch()
            self.assertEqual(select(root), ("hubert_fp16.pt", "lip_fp32.pt"))

    def test_quantized_names_do_not_accept_ordinary_checkpoint_format(self):
        self.assertTrue(callable(getattr(models, "select_model_files", None)),
                        "Quantized model loading is required")
        from inference import quantized
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            torch.save({"state_dict": {}}, root / "hubert_w8a16.pt")
            (root / "lip_mixed_int8.pt").touch()
            # CUDA and the DLL are external here; checkpoint parsing stays real.
            with patch.object(models, "require_cuda"), patch.object(quantized, "Kernels"):
                with self.assertRaisesRegex(ValueError, "checkpoint format"):
                    models.Models(root)


if __name__ == "__main__":
    unittest.main()
