"""Exercise the real Web pump with controlled inference timing, without CUDA or a server.

Only the nanoavatar session/model and the remote socket peer are test doubles.
Viewer.pump, Reply, worker dispatch, JPEG encoding and media framing stay real.
This verifies Web behavior against an inference contract, not GPU timing correctness.
"""
import argparse
import asyncio
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
import hashlib
import json
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import numpy as np

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--web-root", type=Path, default=Path(__file__).resolve().parents[1],
                    help="Target Web directory containing server.py, cloud.py and wire.py")
arguments, unittest_arguments = parser.parse_known_args()
WEB = arguments.web_root.resolve()
if not all((WEB / name).is_file() for name in ("server.py", "cloud.py", "wire.py")):
    parser.error("--web-root must contain server.py, cloud.py and wire.py")
sys.path.insert(0, str(WEB))
from server import Viewer
from wire import HEADER, encode_image, encode_png, decode_frame

DEFINITION = "feature_extraction_start_to_first_generated_rgb"
RGB = np.zeros((32, 32, 3), dtype=np.uint8)
JPEG = encode_image(RGB)
PCM = np.full(640, 0.125, dtype=np.float32)


class Plan:
    def __init__(self, compute_ms, hold_return=False):
        self.compute_ms = compute_ms
        self.first_pcm = threading.Event()
        self.rgb_ready = threading.Event()
        self.release_return = threading.Event()
        if not hold_return:
            self.release_return.set()


class FakeSession:
    """Two PCM frames are needed; RGB computation precedes full-frame return."""
    def __init__(self, model, config):
        self.model = model
        self.plan = next(model.plans)
        self.status = "open"
        self.next_frame = 0
        self.received = 0
        self.stats = {"first_frame_ms": None, "first_frame_definition": DEFINITION}

    def push_audio(self, pcm):
        self.received += len(pcm)
        self.plan.first_pcm.set()
        if self.received < 1280 or self.next_frame:
            return
        self.stats["first_frame_ms"] = self.plan.compute_ms
        self.plan.rgb_ready.set()
        if not self.plan.release_return.wait(3):
            raise TimeoutError("test did not release full-frame return")
        for index in range(2):
            if self.status != "open":
                return
            self.next_frame += 1
            self.model.phase += 1
            yield SimpleNamespace(index=index, rgb=RGB, face_rgb=np.zeros((256, 256, 3), np.uint8), audio=PCM)

    def finish(self):
        self.status = "finished"
        return iter(())

    def cancel(self):
        self.status = "cancelled"


class Model:
    stride = 2

    def __init__(self, plans):
        self.plans = iter(plans)
        self.phase = 0
        self.source = SimpleNamespace(index=lambda phase: 0, masks=[encode_png(np.full((16, 16), 255, np.uint8))],
            presentation=lambda index, kind, blend=1, end_turn=0: dict(kind=kind, blend=blend, end_turn=end_turn))

    def runtime_info(self):
        return {"device": "test fixture, no GPU"}

    def idle(self):
        phase = self.phase
        self.phase += 1
        return phase, RGB

    def idle_jpeg(self, phase):
        return JPEG

    def end_speech(self):
        pass


class Peer:
    """Record actual outbound events and acknowledge each actual media packet."""
    def __init__(self):
        self.events = []
        self.media_turns = []
        self.media = []
        self.changed = asyncio.Event()
        self.viewer = None

    async def send_json(self, event):
        self.events.append(dict(event))
        self.changed.set()

    async def send_bytes(self, packet):
        _, sequence, turn, *_ = HEADER.unpack_from(packet)
        if turn:
            self.media_turns.append(turn)
        self.media.append(decode_frame(packet))
        self.viewer.ack = sequence
        self.viewer.capacity.set()
        self.changed.set()

    async def close(self):
        pass

    @property
    def metrics(self):
        return [event for event in self.events if event["type"] == "metrics"]

    async def wait_done(self, turn):
        async with asyncio.timeout(3):
            while not any(event["type"] == "generation_done" and event["turn"] == turn
                          for event in self.events):
                self.changed.clear()
                await self.changed.wait()


@asynccontextmanager
async def pumping(*plans):
    inference_fixture = SimpleNamespace(StreamingSession=FakeSession, StreamConfig=SimpleNamespace)
    with patch.dict(sys.modules, {"inference": inference_fixture}):
        with ThreadPoolExecutor(max_workers=1) as renderer, ThreadPoolExecutor(max_workers=2) as encoders:
            peer = Peer()
            viewer = Viewer(peer, Model(plans), None, renderer, encoders)
            peer.viewer = viewer
            task = asyncio.create_task(viewer.pump())
            try:
                yield viewer, peer
            finally:
                for plan in plans:
                    plan.release_return.set()
                viewer.closed = True
                viewer.capacity.set()
                if viewer.reply:
                    viewer.reply.cancel()
                await asyncio.wait_for(task, 3)


def feed(reply, pcm, done=False):
    reply.pcm.put_nowait(pcm)
    reply.cloud_done = done
    reply.available.set()


class WebComputeTests(unittest.IsolatedAsyncioTestCase):
    async def test_streaming_gap_advances_source_without_natural_end(self):
        async with pumping(Plan(7)) as (viewer, peer):
            reply = await viewer.new_reply("gap", "audio")
            feed(reply, np.tile(PCM, 2))
            async with asyncio.timeout(3):
                while len(peer.media_turns) < 2 or peer.media[-1]["metadata"]["kind"] != "idle":
                    peer.changed.clear()
                    await peer.changed.wait()
            speech = [p for p in peer.media if p["turn"] == reply.id]
            self.assertGreater(peer.media[-1]["phase"], speech[-1]["phase"])
            self.assertFalse(any(p["metadata"]["kind"] == "end" for p in peer.media))

    async def test_natural_end_has_owner_and_cancel_removes_future_release(self):
        async with pumping(Plan(7)) as (viewer, peer):
            reply = await viewer.new_reply("end", "audio")
            feed(reply, np.tile(PCM, 2), done=True)
            async with asyncio.timeout(3):
                while sum(p["metadata"]["kind"] == "end" for p in peer.media) < 15:
                    peer.changed.clear()
                    await peer.changed.wait()
            ended = [p for p in peer.media if p["metadata"]["kind"] == "end"]
            self.assertEqual(ended[0]["metadata"]["end_turn"], reply.id)
            self.assertIsNone(ended[0]["face"])
            self.assertGreaterEqual(len(ended), 15, "Release must not expire at the producer's tenth frame")
            await viewer.cancel()
            self.assertEqual(viewer.natural_end, 0)
            previous = len(peer.media)
            async with asyncio.timeout(3):
                while len(peer.media) == previous:
                    peer.changed.clear()
                    await peer.changed.wait()
            self.assertEqual(peer.media[-1]["metadata"]["kind"], "idle")

    async def reached(self, event):
        self.assertTrue(await asyncio.to_thread(event.wait, 3), "inference boundary was not reached")

    def evidence(self, peer, **values):
        self.assertFalse([event for event in peer.events if event["type"] == "fatal"])
        print(json.dumps({"case": self._testMethodName, "metrics": peer.metrics,
                          "media_turns": peer.media_turns, **values}, sort_keys=True), flush=True)

    async def test_pcm_wait_is_excluded_and_first_metric_is_emitted_once(self):
        plan = Plan(7.25)
        async with pumping(plan) as (viewer, peer):
            reply = await viewer.new_reply("delayed PCM", "audio")
            feed(reply, PCM)
            await self.reached(plan.first_pcm)
            started = time.perf_counter()
            await asyncio.sleep(0.15)
            waited_ms = (time.perf_counter() - started) * 1000
            self.assertEqual(peer.metrics, [], "No result exists while PCM is insufficient")
            feed(reply, PCM, done=True)
            await peer.wait_done(reply.id)
            self.evidence(peer, injected_pcm_wait_ms=waited_ms, expected_compute_ms=7.25)
            self.assertEqual(peer.media_turns, [reply.id, reply.id])
            self.assertEqual(len(peer.metrics), 1, "Two returned frames must share one first-frame metric")
            self.assertEqual(peer.metrics[0]["turn"], reply.id)
            with self.subTest(boundary="duration"):
                self.assertEqual(peer.metrics[0]["first_frame_ms"], 7.25,
                                 "PCM accumulation must not replace the inference compute duration")
            with self.subTest(boundary="definition"):
                self.assertEqual(peer.metrics[0].get("first_frame_definition"), DEFINITION)

    async def test_full_frame_return_wait_after_rgb_is_excluded(self):
        plan = Plan(12.5, hold_return=True)
        async with pumping(plan) as (viewer, peer):
            reply = await viewer.new_reply("delayed full-frame return", "audio")
            feed(reply, np.tile(PCM, 2), done=True)
            await self.reached(plan.rgb_ready)
            started = time.perf_counter()
            await asyncio.sleep(0.15)
            waited_ms = (time.perf_counter() - started) * 1000
            plan.release_return.set()
            await peer.wait_done(reply.id)
            self.evidence(peer, injected_post_rgb_wait_ms=waited_ms, expected_compute_ms=12.5)
            self.assertEqual(peer.media_turns, [reply.id, reply.id])
            self.assertEqual(len(peer.metrics), 1)
            self.assertEqual(peer.metrics[0]["first_frame_ms"], 12.5,
                             "A blocked full-frame return must not extend face-RGB computation")

    async def test_cancelled_in_flight_turn_cannot_publish_metric_into_new_turn(self):
        previous = Plan(7.25, hold_return=True)
        current = Plan(18.75)
        async with pumping(previous, current) as (viewer, peer):
            old_reply = await viewer.new_reply("old", "audio")
            feed(old_reply, np.tile(PCM, 2), done=True)
            await self.reached(previous.rgb_ready)
            new_reply = await viewer.new_reply("new", "audio")
            feed(new_reply, np.tile(PCM, 2), done=True)
            previous.release_return.set()
            await peer.wait_done(new_reply.id)
            self.evidence(peer, cancelled_turn=old_reply.id, current_turn=new_reply.id)
            self.assertTrue(any(event["type"] == "cancelled" and event["turn"] == old_reply.id
                                for event in peer.events))
            self.assertEqual(peer.media_turns, [new_reply.id, new_reply.id])
            self.assertEqual([p["phase"] for p in peer.media], list(range(len(peer.media))),
                             "Cancelled unsent generation must not skip source-video phases")
            self.assertEqual([event["turn"] for event in peer.metrics], [new_reply.id],
                             "Cancelled computation must not publish a stale or repeated metric")


if __name__ == "__main__":
    print("WEB CONTRACT TEST ONLY: FakeSession replaces CUDA; this is not GPU evidence.", flush=True)
    print("server.py SHA256: " + hashlib.sha256((WEB / "server.py").read_bytes()).hexdigest(), flush=True)
    unittest.main(argv=[sys.argv[0], *unittest_arguments], verbosity=2)
