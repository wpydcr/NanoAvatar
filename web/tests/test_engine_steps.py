"""CPU tensors exercise scheduling only; no neural model or CUDA inference is run."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[1] / "inference"
package = types.ModuleType("engine_test_package")
package.__path__ = [str(ROOT)]
sys.modules[package.__name__] = package
models = types.ModuleType(package.__name__ + ".models")
models.Models = object
sys.modules[models.__name__] = models
spec = importlib.util.spec_from_file_location(package.__name__ + ".engine", ROOT / "engine.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Steps(unittest.TestCase):
    def test_every_media_step_has_its_own_generated_face_and_uncovered_source(self):
        source = types.SimpleNamespace(width=256, height=256, count=3,
            repair=np.zeros((256, 256, 3), np.uint8),
            frames=np.full((3, 256, 256, 3), 30, np.uint8),
            _raw_faces=np.full((3, 256, 256, 3), 30, np.uint8),
            index=lambda phase: phase % 3, prepare=lambda: None, close=lambda: None,
            match_color=lambda face, index: face,
            composite=lambda index, face, strength: face)
        runtime = types.SimpleNamespace(close=lambda: None)
        with patch.object(module, "Models", return_value=runtime):
            engine = module.AvatarEngine(None, "unused")
        engine.source = source
        engine.width = engine.height = 256
        engine.mask = np.ascontiguousarray(source.repair.transpose(2, 0, 1).astype(np.float32)/255)
        def cache(indices):
            for index in indices:
                engine.cache[index] = [np.zeros((1, 1), np.float16)] * 7
        engine.cache_references = cache
        def run(name, inputs):
            if name == "audio":
                return torch.arange(3, dtype=torch.float32).reshape(3, 1), inputs["h"], inputs["c"]
            if name == "generator":
                values = inputs["tensors"][0][:, 0]
                return ((values + 1) / 10)[:, None, None, None].expand(-1, 3, 256, 256)
            raise AssertionError(name)
        engine._run = run
        original_to = torch.Tensor.to
        def cpu_tensor(tensor, *args, **kwargs):
            args = tuple("cpu" if isinstance(value, str) and value.startswith("cuda") else value for value in args)
            if str(kwargs.get("device", "")).startswith("cuda"):
                kwargs["device"] = "cpu"
            return original_to(tensor, *args, **kwargs)
        try:
            with patch.object(torch.Tensor, "to", cpu_tensor):
                rendered, _ = engine.render_batch([np.zeros((10, 1024), np.float32)] * 3,
                                                  (torch.zeros(1), torch.zeros(1)), 0)
            self.assertEqual(engine.generated_frames, 3, "Every 25 Hz audio step must generate a mouth")
            self.assertEqual(len({face.tobytes() for _, face in rendered}), 3)
            for base, _ in rendered:
                self.assertTrue(np.all(base == 30), "Browser needs an uncovered base for cancellation and release")
        finally:
            engine.close()


if __name__ == "__main__":
    unittest.main()
