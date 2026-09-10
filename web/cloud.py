"""DashScope SSE language model + one duplex, streaming PCM TTS task per reply."""
import asyncio
from contextlib import nullcontext
from dataclasses import dataclass, field
import json
import os
import time
import uuid

import aiohttp
import numpy as np

class CloudError(RuntimeError):
    """A safe-to-display error; never include auth headers or raw provider bodies."""


@dataclass
class CloudConfig:
    key: str = field(default_factory=lambda: os.environ.get("DASHSCOPE_API_KEY", "").strip(), repr=False)
    llm_model: str = "qwen-plus"
    tts_model: str = "cosyvoice-v2"
    voice: str = "longxiaochun_v2"
    llm_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    tts_url: str = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    proxy: str | None = None

    def api_key(self):
        key = self.key.strip()
        if not key or any(c.isspace() for c in key):
            raise CloudError("Configure an API key in Settings or DASHSCOPE_API_KEY.")
        return key

    def public(self):
        return dict(key_configured=bool(self.key), llm_model=self.llm_model,
                    tts_model=self.tts_model, voice=self.voice)


async def sse_events(content):
    """aiohttp reassembles lines across arbitrary TCP packets; preserve multi-line data."""
    data = []
    async for raw in content:
        line = raw.decode("utf-8").rstrip("\r\n")
        if not line:
            if data:
                yield "\n".join(data)
                data = []
        elif line.startswith("data:"):
            data.append(line[5:].lstrip(" "))
    if data:
        yield "\n".join(data)


class TextChunks:
    """Start TTS at a short natural boundary, without waiting for the whole answer."""
    def __init__(self):
        self.pending = ""

    def feed(self, text, final=False):
        self.pending += text
        result = []
        while self.pending:
            cut = next((i + 1 for i, char in enumerate(self.pending)
                        if char in "。！？!?；;\n" or (i >= 9 and char in "，,：:")), None)
            if cut is None and len(self.pending) >= 48:
                cut = 48
            if cut is None:
                if final:
                    cut = len(self.pending)
                else:
                    break
            part, self.pending = self.pending[:cut], self.pending[cut:]
            if part.strip():
                result.append(part.strip())
        return result


@dataclass
class Reply:
    id: int
    prompt: str
    # Audio is capped at 90 seconds (5.76 MB float32). Never block the TTS
    # WebSocket reader behind slow inference, which would also block heartbeats.
    pcm: asyncio.Queue = field(default_factory=asyncio.Queue)
    language: str = "zh"
    cancelled: bool = False
    cloud_done: bool = False
    render_done: bool = False
    error: str | None = None
    text: str = ""
    task: asyncio.Task | None = None
    started: float = field(default_factory=time.perf_counter)
    metrics: dict = field(default_factory=dict)
    available: asyncio.Event = field(default_factory=asyncio.Event)

    def mark(self, name):
        self.metrics.setdefault(name, round(time.perf_counter() - self.started, 4))

    def cancel(self):
        self.cancelled = True
        self.available.set()
        if self.task and not self.task.done():
            self.task.cancel()


class DashScope:
    def __init__(self, config):
        self.config = config
        self.client = None

    async def start(self):
        """Keep DNS/TLS/HTTP connections across turns; no request at startup."""
        if self.client is None or self.client.closed:
            self.client = aiohttp.ClientSession(
                connector=aiohttp.TCPConnector(limit=4, limit_per_host=4,
                    ttl_dns_cache=300, keepalive_timeout=60),
                timeout=aiohttp.ClientTimeout(total=None, connect=15, sock_read=45),
                trust_env=False)

    async def close(self):
        if self.client is not None:
            await self.client.close()
            self.client = None

    async def run(self, reply, emit, history=()):
        cfg = self.config
        headers = {"Authorization": "Bearer " + cfg.api_key()}
        timeout = aiohttp.ClientTimeout(total=None, connect=15, sock_read=45)
        # Never inherit proxy settings implicitly. The launcher's process variables
        # are also scoped to the user's explicitly selected connection mode.
        # Standalone validation retains cleanup; the service owns a connection pool.
        connection = (nullcontext(self.client) if self.client is not None else
                      aiohttp.ClientSession(timeout=timeout, trust_env=False))
        async with connection as client:
            texts = asyncio.Queue(maxsize=16)
            async with asyncio.TaskGroup() as group:
                group.create_task(self._llm(client, reply, texts, emit, history, headers))
                group.create_task(self._tts(client, reply, texts, headers))

    async def _llm(self, client, reply, texts, emit, history, headers):
        payload = {
            "model": self.config.llm_model, "stream": True, "enable_thinking": False,
            "max_tokens": 384,
            "messages": [{"role": "system", "content":
                ("Reply naturally in English in two to four spoken sentences. "
                 "Do not use Markdown, lists, emoji or stage directions."
                 if reply.language == "en" else
                 "你是亲切自然的中文语音助手。用两到四句适合口头表达的中文回答。"
                 "不使用 Markdown、列表符号、表情或动作描述。")},
                *list(history)[-8:], {"role": "user", "content": reply.prompt}],
        }
        splitter = TextChunks()
        async with client.post(self.config.llm_url, json=payload, headers=headers,
                               proxy=self.config.proxy) as response:
            reply.mark("llm_connected_seconds")
            if response.status != 200:
                raise CloudError(f"LLM HTTP {response.status}. Check the API key, region and model access.")
            complete = False
            async for event in sse_events(response.content):
                if event == "[DONE]":
                    complete = True
                    break
                data = json.loads(event)
                if "error" in data:
                    raise CloudError("LLM request failed. Check quota and model access.")
                for choice in data.get("choices", []):
                    token = choice.get("delta", {}).get("content") or ""
                    if token:
                        reply.mark("first_llm_text_seconds")
                        reply.text += token
                        await emit({"type": "text", "turn": reply.id, "delta": token})
                        for text in splitter.feed(token):
                            await texts.put(text)
                    if choice.get("finish_reason"):
                        complete = True
            if not complete or not reply.text.strip():
                raise CloudError("LLM stream ended without a complete answer. Please retry.")
        for text in splitter.feed("", final=True):
            await texts.put(text)
        await texts.put(None)
        reply.mark("llm_done_seconds")
        await emit({"type": "text_done", "turn": reply.id})

    async def _tts(self, client, reply, texts, headers):
        cfg = self.config
        task_id = uuid.uuid4().hex
        started = asyncio.Event()
        async with client.ws_connect(cfg.tts_url, proxy=cfg.proxy, headers=headers, heartbeat=20,
                                     max_msg_size=4 * 1024 * 1024) as socket:
            reply.mark("tts_connected_seconds")
            await socket.send_json({
                "header": {"action": "run-task", "task_id": task_id, "streaming": "duplex"},
                "payload": {"task_group": "audio", "task": "tts", "function": "SpeechSynthesizer",
                    "model": cfg.tts_model, "parameters": {"text_type": "PlainText", "voice": cfg.voice,
                        "format": "pcm", "sample_rate": 16000, "volume": 50, "rate": 1, "pitch": 1},
                    "input": {}},
            })

            async def send_text():
                await asyncio.wait_for(started.wait(), timeout=20)
                while True:
                    text = await texts.get()
                    action = "finish-task" if text is None else "continue-task"
                    await socket.send_json({
                        "header": {"action": action, "task_id": task_id, "streaming": "duplex"},
                        "payload": {"input": {} if text is None else {"text": text}},
                    })
                    if text is None:
                        return
                    reply.mark("first_tts_text_sent_seconds")

            async def receive_audio():
                pending = bytearray()
                samples = 0

                async def push(block):
                    nonlocal samples
                    pcm = np.frombuffer(block, dtype="<i2").astype(np.float32) / 32768
                    samples += len(pcm)
                    if samples > 16000 * 90:
                        raise CloudError("Audio exceeded 90 seconds. Try a shorter reply.")
                    for offset in range(0, len(pcm), 6400):
                        reply.pcm.put_nowait(pcm[offset:offset + 6400])
                    reply.available.set()
                    reply.metrics["tts_samples"] = samples
                    reply.metrics["max_pcm_queue_chunks"] = max(
                        reply.metrics.get("max_pcm_queue_chunks", 0), reply.pcm.qsize())

                async for message in socket:
                    if message.type == aiohttp.WSMsgType.BINARY:
                        reply.mark("first_tts_audio_seconds")
                        pending.extend(message.data)
                        # Forward complete PCM samples immediately. The inference owns
                        # model context waiting, independent of network chunks.
                        complete = len(pending) // 2 * 2
                        if complete:
                            await push(bytes(pending[:complete]))
                            del pending[:complete]
                    elif message.type == aiohttp.WSMsgType.TEXT:
                        event = json.loads(message.data)
                        header = event.get("header", {})
                        if header.get("event") == "task-started":
                            reply.mark("tts_started_seconds")
                            started.set()
                        elif header.get("event") == "task-failed":
                            # Do not echo vendor payloads, which can contain submitted data.
                            raise CloudError("TTS failed. Check voice, model access and quota.")
                        elif header.get("event") == "task-finished":
                            if len(pending) % 2:
                                raise CloudError("TTS returned truncated PCM audio.")
                            if pending:
                                await push(bytes(pending))
                            if not samples:
                                raise CloudError("TTS returned no audio.")
                            reply.mark("tts_done_seconds")
                            return
                    elif message.type == aiohttp.WSMsgType.ERROR:
                        raise CloudError("TTS connection interrupted.")
                raise CloudError("TTS stream did not finish. Please retry.")

            async with asyncio.TaskGroup() as group:
                group.create_task(send_text())
                group.create_task(receive_audio())


def public_error(error):
    if isinstance(error, BaseExceptionGroup):
        for child in error.exceptions:
            result = public_error(child)
            if result:
                return result
    if isinstance(error, CloudError):
        return str(error)
    if isinstance(error, (aiohttp.ClientError, asyncio.TimeoutError)):
        return "Cloud connection failed or timed out. Check network and service region."
    return "Processing failed. The server log contains the error type only."
