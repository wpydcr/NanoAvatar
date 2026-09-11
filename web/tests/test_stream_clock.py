"""Sample windows, tail samples and cancellation are independent of transport chunks."""
import importlib.util
from pathlib import Path
import sys
import unittest

import numpy as np

spec = importlib.util.spec_from_file_location("stream_clock", Path(__file__).resolve().parents[1] / "inference/stream.py")
stream = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = stream
spec.loader.exec_module(stream)


class Backend:
    def __init__(self):
        self.resets = 0
        self.windows = []
        self.closed = []
    def new_state(self):
        self.resets += 1
        return self.resets
    def _acquire_session(self, session):
        pass
    def _release_session(self, session):
        self.closed.append(session.status)
    def begin_speech(self):
        pass
    def extract(self, pcm):
        self.windows.append(pcm.copy())
        return np.zeros((max(1, (len(pcm) - 400) // 320 + 1), 1024), np.float32)
    def render(self, window, state):
        return np.zeros((1, 1, 3), np.uint8), np.zeros((256, 256, 3), np.uint8), state


class SampleClock(unittest.TestCase):
    def test_irregular_chunks_preserve_every_tail_sample_and_context(self):
        pcm = np.linspace(-.5, .5, 23041, dtype=np.float32)
        outputs = []
        for pieces in ([len(pcm)], [1, 97, 8000, 200, 4000, 10743]):
            backend = Backend()
            session = stream.StreamingSession(backend)
            packets = []
            position = 0
            for size in pieces:
                packets.extend(session.push_audio(pcm[position:position + size]))
                position += size
            packets.extend(session.finish())
            self.assertEqual(position, len(pcm))
            self.assertEqual(len(packets), 37)
            np.testing.assert_array_equal(np.concatenate([p.audio for p in packets]), pcm)
            self.assertEqual([p.index for p in packets], list(range(37)))
            self.assertEqual(backend.resets, 5)  # Initial state plus frames 0, 10, 20, 30.
            self.assertEqual(backend.closed, ["finished"])
            self.assertEqual(max(map(len, backend.windows)), 28800 if len(pcm) > 28800 else len(pcm))
            outputs.append(backend.windows)
        for a, b in zip(*outputs):
            np.testing.assert_array_equal(a, b)

    def test_cancelled_iterator_never_emits_buffered_tail_or_natural_end(self):
        backend = Backend()
        session = stream.StreamingSession(backend)
        iterator = session.push_audio(np.zeros(12800, np.float32))
        self.assertEqual(next(iterator).index, 0)
        session.cancel()
        self.assertEqual(list(iterator), [])
        self.assertEqual(backend.closed, ["cancelled"])
        self.assertEqual(session.buffer_samples, 0)
        with self.assertRaises(RuntimeError):
            session.finish()


if __name__ == "__main__":
    unittest.main()
