"""Web lifecycle and WAV regressions; no GPU, cloud request or inference mock import."""
import asyncio
from io import BytesIO
from pathlib import Path
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from unittest.mock import patch
import unittest
import wave

from aiohttp import web
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud import CloudConfig
from server import create_app, read_wav, Viewer
from wire import decode_frame, encode_image, encode_png


class Engine:
    width, height = 256, 256

    def __init__(self, fail_warmup=False, fail_close=False):
        self.calls = [("load", threading.get_ident())]
        self.fail_warmup, self.fail_close = fail_warmup, fail_close

    def warmup(self):
        self.calls.append(("warmup", threading.get_ident()))
        if self.fail_warmup:
            raise RuntimeError("warmup failed")

    def runtime_info(self):
        self.calls.append(("runtime", threading.get_ident()))
        return {"device": "test fixture", "providers": []}

    def close(self):
        self.calls.append(("close", threading.get_ident()))
        if self.fail_close:
            raise RuntimeError("close failed")


class LifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def test_load_warmup_and_close_use_same_worker(self):
        engines = []

        def load():
            engine = Engine()
            engines.append(engine)
            return engine

        runner = web.AppRunner(create_app(config=CloudConfig(key=""), model_factory=load))
        await runner.setup()
        await runner.cleanup()
        await runner.cleanup()
        calls = engines[0].calls
        self.assertEqual([name for name, _ in calls], ["load", "warmup", "runtime", "close"])
        self.assertEqual(len({ident for _, ident in calls}), 1)
        self.assertNotEqual(calls[0][1], threading.get_ident())
        self.assert_no_workers()

    async def test_warmup_failure_closes_engine_and_worker(self):
        engines = []

        def load():
            engine = Engine(fail_warmup=True)
            engines.append(engine)
            return engine

        runner = web.AppRunner(create_app(config=CloudConfig(key=""), model_factory=load))
        with self.assertRaisesRegex(RuntimeError, "warmup failed"):
            await runner.setup()
        await runner.cleanup()
        self.assertEqual([name for name, _ in engines[0].calls], ["load", "warmup", "close"])
        self.assert_no_workers()

    async def test_load_failure_does_not_leave_worker(self):
        def load():
            raise RuntimeError("CUDA admission failed")

        runner = web.AppRunner(create_app(config=CloudConfig(key=""), model_factory=load))
        with self.assertRaisesRegex(RuntimeError, "CUDA admission failed"):
            await runner.setup()
        await runner.cleanup()
        self.assert_no_workers()

    async def test_close_failure_still_shuts_down_worker(self):
        runner = web.AppRunner(create_app(config=CloudConfig(key=""),
                                         model_factory=lambda: Engine(fail_close=True)))
        await runner.setup()
        with self.assertRaisesRegex(RuntimeError, "close failed"):
            await runner.cleanup()
        self.assert_no_workers()

    def assert_no_workers(self):
        self.assertFalse([thread.name for thread in threading.enumerate()
                          if thread.name.startswith(("nanoavatar-inference", "nanoavatar-jpeg"))])


class WavTests(unittest.TestCase):
    @staticmethod
    def wav(pcm, rate=16000):
        output = BytesIO()
        with wave.open(output, "wb") as target:
            target.setparams((1, 2, rate, len(pcm), "NONE", "not compressed"))
            target.writeframes(np.asarray(pcm, dtype="<i2").tobytes())
        return output.getvalue()

    def test_short_tail_keeps_every_sample(self):
        expected = np.array([-32768, 0, 32767] + [512] * 638, dtype="<i2")
        actual = read_wav(self.wav(expected))
        self.assertEqual(actual.dtype, np.float32)
        np.testing.assert_array_equal(actual, expected.astype(np.float32) / 32768)

    def test_rejects_truncation_wrong_rate_and_empty_input(self):
        for data in (b"invalid", self.wav([1, 2, 3])[:-2], self.wav([1], 48000), self.wav([])):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                read_wav(data)


class PullTests(unittest.TestCase):
    def test_first_pull_does_not_compute_the_rest(self):
        calls = []
        def produce():
            calls.append("first")
            yield 1
            calls.append("rest")
            yield 2
        iterator = produce()
        self.assertEqual(Viewer.pull_packets(iterator, 1), ([1], False))
        self.assertEqual(calls, ["first"])
        iterator.close()
        self.assertEqual(calls, ["first"])

    def test_short_tail_is_drained_in_order(self):
        iterator = iter(range(13))
        self.assertEqual(Viewer.pull_packets(iterator, 1), ([0], False))
        self.assertEqual(Viewer.pull_packets(iterator, 9), (list(range(1, 10)), False))
        self.assertEqual(Viewer.pull_packets(iterator, 10), ([10, 11, 12], True))


class BatchTests(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def source():
        return SimpleNamespace(index=lambda phase: phase % 7, masks=[encode_png(np.full((16, 16), 255, np.uint8))] * 7,
                               presentation=lambda index, kind, blend=1, end_turn=0: dict(kind=kind, blend=blend, end_turn=end_turn))

    async def test_parallel_encoding_preserves_audio_and_sequence(self):
        sent = []

        async def send_bytes(data):
            sent.append(decode_frame(data))

        socket = SimpleNamespace(send_bytes=send_bytes)
        model = SimpleNamespace(stride=2, source=self.source())
        reply = SimpleNamespace(id=1, cancelled=False, error=None)
        slow_started, release = threading.Event(), threading.Event()

        def encode(rgb):
            if rgb[0, 0, 0] == 0:
                slow_started.set()
                if not release.wait(3):
                    raise TimeoutError('first encoder not released')
            else:
                release.set()
            return encode_image(rgb)

        packets = [SimpleNamespace(index=i, rgb=np.full((32, 32, 3), i * 30, np.uint8),
                   face_rgb=np.full((256, 256, 3), 100 + i, np.uint8), audio=np.full(640, i / 8, np.float32)) for i in range(3)]
        with ThreadPoolExecutor(max_workers=1) as renderer, ThreadPoolExecutor(max_workers=2) as encoders:
            viewer = Viewer(socket, model, None, renderer, encoders)
            viewer.reply = reply
            with patch('server.encode_image', encode):
                await viewer.send_batch(20, packets, reply)
        self.assertTrue(slow_started.is_set())
        self.assertEqual([p['sequence'] for p in sent], [0, 1, 2])
        self.assertEqual([p['phase'] for p in sent], [20, 21, 22])
        self.assertEqual([p['generated'] for p in sent], [True, True, True])
        for index, packet in enumerate(sent):
            np.testing.assert_array_equal(packet['pcm'], np.full(640, index * 4096, np.int16))
            np.testing.assert_array_equal(packet['face'], packets[index].face_rgb)
            self.assertEqual(packet['metadata']['kind'], 'speech')

    async def test_cancel_during_encoding_discards_the_old_turn(self):
        sent = []

        async def send_bytes(data):
            sent.append(data)

        model = SimpleNamespace(stride=1, source=self.source())
        reply = SimpleNamespace(id=1, cancelled=False, error=None)
        started, release = threading.Event(), threading.Event()

        def encode(rgb):
            started.set()
            if not release.wait(3):
                raise TimeoutError('encoder not released')
            return encode_image(rgb)

        packet = SimpleNamespace(index=0, rgb=np.zeros((32, 32, 3), np.uint8), face_rgb=np.zeros((256, 256, 3), np.uint8), audio=np.zeros(640, np.float32))
        with ThreadPoolExecutor(max_workers=1) as renderer, ThreadPoolExecutor(max_workers=2) as encoders:
            viewer = Viewer(SimpleNamespace(send_bytes=send_bytes), model, None, renderer, encoders)
            viewer.reply = reply
            with patch('server.encode_image', encode):
                task = asyncio.create_task(viewer.send_batch(0, [packet], reply))
                self.assertTrue(await asyncio.to_thread(started.wait, 3))
                reply.cancelled = True
                release.set()
                await task
        self.assertEqual(sent, [])
        self.assertEqual(viewer.sequence, 0)


if __name__ == "__main__":
    unittest.main()
