"""Single-viewer demo. The inference package runs the models; this module owns transport and chat."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from io import BytesIO
import json
import logging
from pathlib import Path
import time
from urllib.parse import urlsplit
import wave

from aiohttp import web, WSMsgType
import numpy as np

from cloud import CloudConfig, DashScope, Reply, public_error
from wire import encode_frame, encode_image, encode_png

STATIC = Path(__file__).parent / "static"
LOG = logging.getLogger("nanoavatar.web")
MAX_AUDIO = 16000 * 90


def read_wav(data):
    if len(data) > MAX_AUDIO * 2 + 65536:
        raise ValueError("Use a WAV no longer than 90 seconds.")
    try:
        with wave.open(BytesIO(data), "rb") as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
                raise ValueError("Use 16 kHz mono PCM16 WAV audio.")
            count = source.getnframes()
            if not 0 < count <= MAX_AUDIO:
                raise ValueError("Use a WAV between one sample and 90 seconds.")
            raw = source.readframes(count)
            if len(raw) != count * 2:
                raise ValueError("WAV audio is truncated.")
    except (wave.Error, EOFError):
        raise ValueError("Use an uncompressed PCM16 WAV file.") from None
    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768


class Viewer:
    def __init__(self, socket, model, cloud, renderer, encoders):
        self.socket, self.model, self.cloud = socket, model, cloud
        self.renderer, self.encoders = renderer, encoders
        self.sequence, self.ack = 0, -1
        self.capacity = asyncio.Event()
        self.reply = None
        self.turn_counter = 0
        self.history = []
        self.language = "zh"
        self.closed = False
        self.tasks = set()
        self.last_runtime = None
        self.natural_end = 0
        self.next_phase = getattr(model, "phase", 0)

    async def render_call(self, function, *args):
        return await asyncio.get_running_loop().run_in_executor(self.renderer, function, *args)

    async def emit(self, event):
        if not self.closed:
            await self.socket.send_json(event)

    async def cancel(self):
        self.natural_end = 0
        if self.reply:
            self.reply.cancel()
            await self.emit({"type": "cancelled", "turn": self.reply.id})

    async def new_reply(self, prompt, kind="chat"):
        await self.cancel()
        self.turn_counter += 1
        reply = Reply(self.turn_counter, prompt, language=self.language)
        self.reply = reply
        await self.emit({"type": "turn", "turn": reply.id, "prompt": prompt, "kind": kind})
        return reply

    async def cloud_reply(self, reply, history, config):
        # A settings edit applies to the next reply, never halfway through a turn.
        cloud = DashScope(config)
        cloud.client = self.cloud.client
        try:
            async with asyncio.timeout(150):
                await cloud.run(reply, self.emit, history)
        except asyncio.CancelledError:
            pass
        except Exception as error:
            reply.error = public_error(error)
            LOG.error("Cloud request failed: %s", type(error).__name__)
            await self.emit({"type": "error", "turn": reply.id, "message": reply.error})
        finally:
            reply.cloud_done = True
            reply.available.set()

    async def receive(self):
        async for message in self.socket:
            try:
                if message.type == WSMsgType.BINARY:
                    pcm = read_wav(message.data)
                    reply = await self.new_reply("WAV", "audio")
                    for offset in range(0, len(pcm), 6400):
                        reply.pcm.put_nowait(pcm[offset:offset + 6400])
                    reply.cloud_done = True
                    reply.available.set()
                    continue
                if message.type != WSMsgType.TEXT:
                    continue
                data = json.loads(message.data)
                if not isinstance(data, dict):
                    raise ValueError("Invalid message.")
                kind = data.get("type")
                if kind == "ack":
                    sequence = data.get("sequence")
                    if type(sequence) is int and self.ack <= sequence < self.sequence:
                        self.ack = sequence
                        self.capacity.set()
                elif kind == "language":
                    if data.get("language") in ("zh", "en"):
                        self.language = data["language"]
                elif kind == "send":
                    prompt = data.get("text")
                    if not isinstance(prompt, str) or not 1 <= len(prompt.strip()) <= 1000:
                        raise ValueError("Enter 1 to 1000 characters.")
                    self.cloud.config.api_key()
                    reply = await self.new_reply(prompt.strip())
                    reply.task = asyncio.create_task(self.cloud_reply(
                        reply, list(self.history), replace(self.cloud.config)))
                    self.tasks.add(reply.task)
                    reply.task.add_done_callback(self.tasks.discard)
                elif kind == "cancel":
                    await self.cancel()
                elif kind == "clear":
                    await self.cancel()
                    self.reply = None
                    self.history.clear()
                    await self.emit({"type": "cleared"})
                elif kind == "settings":
                    cfg = self.cloud.config
                    values = {}
                    for field in ("llm_model", "tts_model", "voice"):
                        value = data.get(field, getattr(cfg, field))
                        if not isinstance(value, str) or not 1 <= len(value) <= 128 or any(c.isspace() for c in value):
                            raise ValueError("Model and voice names must be nonempty identifiers.")
                        values[field] = value
                    key = data.get("key")
                    if key is not None and (not isinstance(key, str) or len(key) > 1024 or any(c.isspace() for c in key)):
                        raise ValueError("Invalid API key format.")
                    for field, value in values.items():
                        setattr(cfg, field, value)
                    if data.get("remove_key") is True:
                        cfg.key = ""
                    elif key:
                        cfg.key = key
                    await self.emit({"type": "settings", **cfg.public()})
            except (ValueError, TypeError, KeyError, RuntimeError) as error:
                # Validation and CloudError messages here never contain submitted values.
                await self.emit({"type": "error", "message": str(error)})

    async def wait_capacity(self, limit):
        while not self.closed and self.sequence - self.ack > limit:
            self.capacity.clear()
            await asyncio.wait_for(self.capacity.wait(), timeout=45)
        if self.closed:
            raise ConnectionError("Viewer closed")

    async def send_frame(self, phase, rgb, audio=(), reply=None, jpeg=None, generated=False, face=b"", frame_index=0):
        await self.wait_capacity(20 if reply else 10)
        if jpeg is None:
            jpeg = await asyncio.get_running_loop().run_in_executor(self.encoders, encode_image, rgb)
        # Do not publish a frame rendered while a cancelled turn was in flight.
        if reply and (reply is not self.reply or reply.cancelled or reply.error):
            return
        turn = reply.id if reply else 0
        source_index = self.model.source.index(phase)
        kind = "speech" if reply else "end" if self.natural_end else "idle"
        metadata = self.model.source.presentation(source_index, kind,
                    min(1., (frame_index + 1) / 5) if reply else 1., self.natural_end if not reply else 0)
        packet = encode_frame(self.sequence, turn, phase, source_index,
                              rgb, audio, jpeg, generated, metadata=metadata,
                              face=face, mask=self.model.source.masks[source_index])
        await self.socket.send_bytes(packet)
        self.sequence += 1
        self.next_phase = phase + 1

    async def send_batch(self, phase, packets, reply):
        loop = asyncio.get_running_loop()
        def encode(packet):
            return encode_image(packet.rgb), encode_png(packet.face_rgb)
        futures = [loop.run_in_executor(self.encoders, encode, p) for p in packets]
        try:
            for index, (packet, future) in enumerate(zip(packets, futures)):
                jpeg, face = await future
                await self.send_frame(phase + index, packet.rgb, packet.audio, reply,
                                      jpeg, True, face, packet.index)
        finally:
            await asyncio.gather(*futures, return_exceptions=True)

    @staticmethod
    def pull_packets(iterator, limit):
        """Pull only this batch; never consume ahead while preparing the first packet."""
        packets = []
        for _ in range(limit):
            try:
                packets.append(next(iterator))
            except StopIteration:
                return packets, True
        return packets, False

    async def pump(self):
        from inference import StreamingSession, StreamConfig
        active = session = finishing = pushing = None
        speaking = False
        pending_send = None
        try:
            while not self.closed:
                await self.wait_capacity(20 if speaking else 10)
                if self.reply is not active:
                    self.natural_end = 0
                    if pending_send is not None:
                        await pending_send
                        pending_send = None
                    if session and session.status == "open":
                        await self.render_call(session.cancel)
                    for iterator in (pushing, finishing):
                        if iterator is not None:
                            await self.render_call(iterator.close)
                    if speaking:
                        await self.render_call(self.model.end_speech)
                    # Generation may run ahead of transport. Only published
                    # base frames consume source-video phase across cancellation.
                    await self.render_call(setattr, self.model, "phase", self.next_phase)
                    active = self.reply
                    session = (await self.render_call(StreamingSession, self.model, StreamConfig(block_frames=10))
                               if active else None)
                    finishing, pushing, speaking = None, None, False
                if active and (active.cancelled or active.error) and not active.render_done:
                    self.natural_end = 0
                    if pending_send is not None:
                        await pending_send
                        pending_send = None
                    await self.render_call(session.cancel)
                    for iterator in (pushing, finishing):
                        if iterator is not None:
                            await self.render_call(iterator.close)
                    pushing = finishing = None
                    active.render_done = True
                    if speaking:
                        await self.render_call(self.model.end_speech)
                        speaking = False
                    await self.render_call(setattr, self.model, "phase", self.next_phase)
                    while not active.pcm.empty():
                        active.pcm.get_nowait()
                packets = []
                phase = self.model.phase
                if active and not active.render_done:
                    # Stay on the inference worker throughout each lazy inference iterator.
                    # 1, then 9, then 10 packets preserves the inference block boundaries.
                    limit = 1 if session.next_frame == 0 else 10 - session.next_frame % 10
                    if pushing is None and finishing is None:
                        try:
                            pcm = active.pcm.get_nowait()
                        except asyncio.QueueEmpty:
                            pcm = None
                        if pcm is not None:
                            pushing = await self.render_call(session.push_audio, pcm)
                        elif active.cloud_done:
                            finishing = await self.render_call(session.finish)
                    iterator = pushing if pushing is not None else finishing
                    if iterator is not None:
                        packets, exhausted = await self.render_call(self.pull_packets, iterator, limit)
                        if exhausted:
                            if iterator is pushing:
                                pushing = None
                            else:
                                finishing = None
                        if packets:
                            speaking = True
                    runtime = await self.render_call(self.model.runtime_info)
                    if runtime != self.last_runtime:
                        self.last_runtime = runtime
                        await self.emit({"type": "runtime", "runtime": runtime})
                    if (packets and active is self.reply and not active.cancelled and not active.error
                            and "first_frame_ms" not in active.metrics):
                        stats = session.stats
                        if stats.get("first_frame_ms") is not None:
                            active.metrics.update(first_frame_ms=stats["first_frame_ms"],
                                                  first_frame_definition=stats["first_frame_definition"])
                            await self.emit({"type": "metrics", "turn": active.id, **active.metrics})
                    if packets:
                        # At most two ten-frame batches: encode/send the previous
                        # batch while the single inference worker renders this one.
                        if pending_send is not None:
                            await pending_send
                        pending_send = asyncio.create_task(self.send_batch(phase, packets, active))
                    if session.status == "finished":
                        if pending_send is not None:
                            await pending_send
                            pending_send = None
                        active.render_done = True
                        if active is self.reply and not active.cancelled and not active.error:
                            self.natural_end = active.id
                            if active.text:
                                self.history.extend([{"role": "user", "content": active.prompt},
                                                     {"role": "assistant", "content": active.text}])
                                self.history = self.history[-8:]
                            await self.emit({"type": "generation_done", "turn": active.id,
                                             "last_sequence": self.sequence - 1})
                        await self.render_call(self.model.end_speech)
                        speaking = False
                if not packets:
                    if pending_send is not None:
                        await pending_send
                        pending_send = None
                    if active and not active.render_done:
                        if not active.pcm.empty():
                            continue
                        # During a network gap the source keeps its phase and the
                        # browser fades the last actually displayed mouth. Audio
                        # and LSTM steps still advance only on accepted samples.
                    phase, rgb = await self.render_call(self.model.idle)
                    idle_jpeg = getattr(self.model, "idle_jpeg", None)
                    jpeg = await self.render_call(idle_jpeg, phase) if idle_jpeg else None
                    await self.send_frame(phase, rgb, jpeg=jpeg)
        except (ConnectionError, asyncio.TimeoutError):
            pass
        except Exception as error:
            LOG.error("Renderer failed: %s", type(error).__name__)
            await self.emit({"type": "fatal", "message": "Avatar rendering failed. Check the model weights and person package."})
        finally:
            if pending_send is not None:
                pending_send.cancel()
                await asyncio.gather(pending_send, return_exceptions=True)
            if session and session.status == "open":
                await self.render_call(session.cancel)
            for iterator in (pushing, finishing):
                if iterator is not None:
                    await self.render_call(iterator.close)
            if speaking:
                await self.render_call(self.model.end_speech)
            await self.socket.close()

    async def run(self):
        self.last_runtime = await self.render_call(self.model.runtime_info)
        await self.emit({"type": "ready", "width": self.model.width, "height": self.model.height,
                         "fps": 25, "stride": self.model.stride, "runtime": self.last_runtime,
                         **self.cloud.config.public()})
        pump = asyncio.create_task(self.pump())
        try:
            await self.receive()
        finally:
            self.closed = True
            self.capacity.set()
            if self.reply:
                self.reply.cancel()
            for task in self.tasks:
                task.cancel()
            await asyncio.gather(*list(self.tasks), return_exceptions=True)
            # Inference must finish before admitting a replacement viewer.
            await pump


def create_app(model=None, config=None, *, model_factory=None):
    """Own the engine and worker lifetime, including partially failed startup."""
    if (model is None) == (model_factory is None):
        raise ValueError("Provide exactly one model or model_factory")
    app = web.Application(client_max_size=MAX_AUDIO * 2 + 65536)
    cloud = DashScope(config or CloudConfig())
    renderer = ThreadPoolExecutor(max_workers=1, thread_name_prefix="nanoavatar-inference")
    encoders = ThreadPoolExecutor(max_workers=2, thread_name_prefix="nanoavatar-jpeg")
    current = None

    async def home(request):
        return web.FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-store"})

    async def health(request):
        return web.json_response(dict(status="ready", width=model.width, height=model.height,
                                      runtime=await asyncio.get_running_loop().run_in_executor(renderer, model.runtime_info),
                                      **cloud.config.public()))

    async def websocket(request):
        nonlocal current
        origin = request.headers.get("Origin")
        if origin and urlsplit(origin).netloc != request.host:
            raise web.HTTPForbidden(text="Same-origin page required")
        socket = web.WebSocketResponse(heartbeat=20, max_msg_size=MAX_AUDIO * 2 + 65536, compress=False)
        await socket.prepare(request)
        if current is not None:
            await socket.send_json({"type": "fatal", "message": "This demo is already open in another tab. Close that tab first."})
            await socket.close()
            return socket
        current = Viewer(socket, model, cloud, renderer, encoders)
        try:
            await current.run()
        finally:
            current = None
        return socket

    async def lifetime(application):
        nonlocal model
        loop = asyncio.get_running_loop()
        try:
            if model is None:
                LOG.info("Loading the local CUDA models and person package...")
                model = await loop.run_in_executor(renderer, model_factory)
            await loop.run_in_executor(renderer, model.warmup)
            LOG.info("Inference ready: %s", await loop.run_in_executor(renderer, model.runtime_info))
            await cloud.start()
            yield
        finally:
            try:
                await cloud.close()
            finally:
                try:
                    if model is not None:
                        await loop.run_in_executor(renderer, model.close)
                finally:
                    renderer.shutdown(wait=True)
                    encoders.shutdown(wait=True)

    async def shutdown(application):
        if current:
            await current.socket.close(code=1001, message=b"Server shutdown")

    app.router.add_get("/", home)
    app.router.add_get("/health", health)
    app.router.add_get("/ws", websocket)
    app.router.add_static("/static/", STATIC, show_index=False)
    app.cleanup_ctx.append(lifetime)
    app.on_shutdown.append(shutdown)
    return app
