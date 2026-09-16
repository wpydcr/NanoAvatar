"""Real portable weights with a CPU device shim; this does not validate CUDA.

Set NANOAVATAR_TEST_MODELS to the directory containing both TorchScript files.
Run this integration test in its own process; without the setting it is skipped.
"""
from contextlib import ExitStack
import ctypes
import os
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

import numpy as np
import torch

WEB = Path(__file__).resolve().parents[1]
MODEL_DIRECTORY = Path(os.environ["NANOAVATAR_TEST_MODELS"]) if os.environ.get("NANOAVATAR_TEST_MODELS") else None


class PortableModelsCPUDeviceShim(unittest.TestCase):
    @unittest.skipUnless(MODEL_DIRECTORY and all((MODEL_DIRECTORY / name).is_file() for name in
                            ("hubert_w8a16.pt", "lip_mixed_int8.pt")),
                         "Set NANOAVATAR_TEST_MODELS to the portable TorchScript pair")
    @torch.inference_mode()
    def test_real_portable_models_preserve_streaming_and_fp16_reference_cache(self):
        sys.path.insert(0, str(WEB))
        from inference import engine as engine_module, models as models_module
        from inference.stream import StreamingSession

        original_to, original_cdll = torch.Tensor.to, ctypes.CDLL

        def cpu_device(value):
            return "cpu" if str(value).startswith("cuda") else value

        def tensor_to(tensor, *args, **kwargs):
            args = tuple(cpu_device(value) if isinstance(value, (str, torch.device))
                         else value for value in args)
            if "device" in kwargs:
                kwargs["device"] = cpu_device(kwargs["device"])
            return original_to(tensor, *args, **kwargs)

        def zeros(*args, **kwargs):
            if "device" in kwargs:
                kwargs["device"] = cpu_device(kwargs["device"])
            return torch.zeros(*args, **kwargs)

        def forbid_custom_dll(path, *args, **kwargs):
            if "native_quant" in str(path).lower():
                raise AssertionError("Portable TorchScript models must not load a custom quantization DLL")
            return original_cdll(path, *args, **kwargs)

        # Only device boundaries change. Models.run and all neural graphs stay real.
        cpu_torch = types.SimpleNamespace(**vars(torch))
        cpu_torch.device = lambda value: torch.device(cpu_device(value))
        cpu_torch.zeros = zeros
        with ExitStack() as stack:
            stack.enter_context(patch.object(models_module, "require_cuda", lambda: None))
            stack.enter_context(patch.object(models_module, "torch", cpu_torch))
            stack.enter_context(patch.object(engine_module, "torch", cpu_torch))
            stack.enter_context(patch.object(torch.Tensor, "to", tensor_to))
            stack.enter_context(patch.object(ctypes, "CDLL", forbid_custom_dll))
            runtime = engine_module.AvatarEngine(None, MODEL_DIRECTORY)
            stack.callback(runtime.close)
            self.assertTrue(runtime.models.quantized)
            hubert, lip = runtime.models.hubert, runtime.models.lip
            self.assertIsInstance(hubert, torch.jit.ScriptModule)
            self.assertIsInstance(lip, torch.jit.ScriptModule)

            rng = np.random.default_rng(23)
            pcm = rng.normal(size=4160).astype(np.float32)
            normalized = (pcm - pcm.mean()) / np.sqrt(pcm.var() + 1e-7)
            expected_features = hubert(torch.from_numpy(normalized[None]))[0].float()
            features = runtime.stream_extract(pcm)
            self.assertEqual(features.shape, (12, 1024))
            self.assertEqual(features.dtype, torch.float32)
            self.assertTrue(torch.isfinite(features).all())
            torch.testing.assert_close(features, expected_features, rtol=1e-5, atol=1e-5)

            # A synthetic decoded source bypasses detection, not any neural stage.
            faces = rng.integers(0, 256, (2, 3, 256, 256), dtype=np.uint8)
            frames = faces.transpose(0, 2, 3, 1).copy()
            runtime.source = types.SimpleNamespace(
                faces=faces, frames=frames, index=lambda phase: phase % 2,
                match_color=lambda face, index: face, close=lambda: None)
            runtime.width = runtime.height = 256
            runtime.mask = np.ones((3, 256, 256), np.float32)
            runtime.mask[:, 128:, 64:192] = 0
            windows = torch.stack([features[i:i + 10] for i in range(3)])
            h, c = runtime.new_state()
            self.assertEqual(h.shape, (2, 1, 576))
            self.assertEqual(c.shape, (2, 1, 576))
            h.fill_(0.125)
            c.fill_(-0.25)

            # Native exported methods are the independent interface reference.
            first_audio, h9, c9 = lip.audio(windows[:1], h, c, 9)
            next_audio, h11, c11 = lip.audio(windows[1:], h9, c9, 10)
            reset_audio, reset_h, reset_c = lip.audio(
                windows[1:], torch.zeros_like(h), torch.zeros_like(c), 10)
            for actual, expected in zip((next_audio, h11, c11),
                                        (reset_audio, reset_h, reset_c)):
                torch.testing.assert_close(actual, expected)
            expected_skips = []
            for face in faces:
                face = face[None].astype(np.float32) / 255
                mask = runtime.mask[None]
                image = torch.from_numpy(np.concatenate((mask[:, :1], face * mask, face), axis=1))
                native_skips = lip.face(image)
                self.assertEqual(len(native_skips), 7)
                rounded = [skip.half().float() for skip in native_skips]
                self.assertTrue(any(not torch.equal(a, b) for a, b in zip(native_skips, rounded)),
                                "Fixture must exercise FP16 host-cache rounding")
                expected_skips.append(rounded)
            expected_first = lip.generate(first_audio, expected_skips[0])
            expected_next = lip.generate(next_audio, [torch.cat((second, first)) for first, second
                                                      in zip(*expected_skips)])

            first_frames, state9 = runtime.render_batch(windows[:1], (h, c), start_frame=9)
            for actual, expected in zip(state9, (h9, c9)):
                torch.testing.assert_close(actual, expected)
            next_frames, state11 = runtime.render_batch(windows[1:], state9, start_frame=10)
            self.assertEqual((len(first_frames), len(next_frames)), (1, 2))
            for actual, expected in zip(state11, (h11, c11)):
                self.assertTrue(torch.isfinite(actual).all())
                torch.testing.assert_close(actual, expected)
            self.assertEqual((runtime.phase, runtime.generated_frames, runtime.speech_frames), (3, 3, 3))
            expected_rgb = torch.cat((expected_first, expected_next))
            self.assertTrue(torch.isfinite(expected_rgb).all())
            expected_pixels = (expected_rgb.permute(0, 2, 3, 1).clamp(0, 1) * 255).round().byte().numpy()
            for index, ((base, face), expected) in enumerate(zip(first_frames + next_frames, expected_pixels)):
                self.assertEqual(face.shape, (256, 256, 3))
                self.assertEqual(face.dtype, np.uint8)
                np.testing.assert_array_equal(base, frames[index % 2])
                self.assertLessEqual(np.abs(face.astype(np.int16) - expected.astype(np.int16)).max(), 1)
            for index, skips in runtime.cache.items():
                for cached, expected in zip(skips, expected_skips[index]):
                    self.assertEqual(cached.dtype, np.float16)
                    np.testing.assert_allclose(cached.astype(np.float32), expected.numpy(), rtol=1e-3, atol=1e-5)

            # The real scheduler must retain the one-sample tail across reset 10.
            speech = rng.uniform(-0.5, 0.5, 6401).astype(np.float32)
            with StreamingSession(runtime) as session:
                packets = list(session.push_audio(speech[:101]))
                packets.extend(session.push_audio(speech[101:]))
                packets.extend(session.finish())
                self.assertEqual(session.status, "finished")
                self.assertEqual(session.buffer_samples, 0)
            self.assertEqual([packet.index for packet in packets], list(range(11)))
            np.testing.assert_array_equal(np.concatenate([packet.audio for packet in packets]), speech)
            self.assertEqual(len(packets[-1].audio), 1)
            for packet in packets:
                self.assertEqual(packet.face_rgb.shape, (256, 256, 3))
                self.assertTrue(np.isfinite(packet.face_rgb).all())

            with StreamingSession(runtime) as cancelled:
                pending = cancelled.push_audio(np.tile(speech, 2)[:12800])
                self.assertEqual(next(pending).index, 0)
                phase = runtime.phase
                cancelled.cancel()
                self.assertEqual(list(pending), [])
                self.assertEqual(runtime.phase, phase)
                self.assertEqual(cancelled.buffer_samples, 0)
                with self.assertRaises(RuntimeError):
                    cancelled.finish()
            with StreamingSession(runtime) as restarted:
                fresh = list(restarted.push_audio(speech[:321]))
                fresh.extend(restarted.finish())
                self.assertEqual(restarted.status, "finished")
            self.assertEqual([packet.index for packet in fresh], [0])
            np.testing.assert_array_equal(fresh[0].audio, speech[:321])
            self.assertEqual(fresh[0].face_rgb.shape, (256, 256, 3))
            self.assertFalse(runtime.closed)


if __name__ == "__main__":
    unittest.main()
