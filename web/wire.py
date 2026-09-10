"""Source JPEG, lossless mouth/mask layers, and 40 ms PCM share each packet."""
import json
import struct

import cv2
import numpy as np

HEADER = struct.Struct("<4sIIIIIIIIII")
MAGIC = b"NAV2"
SAMPLES = 640
SILENCE = bytes(SAMPLES * 2)


def encode_image(rgb):
    ok, jpeg = cv2.imencode(".jpg", cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR),
                            [cv2.IMWRITE_JPEG_QUALITY, 95,
                             cv2.IMWRITE_JPEG_SAMPLING_FACTOR, cv2.IMWRITE_JPEG_SAMPLING_FACTOR_444])
    if not ok:
        raise RuntimeError("JPEG encoder failed")
    return jpeg.tobytes()


def encode_png(rgb):
    pixels = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR) if rgb.ndim == 3 else rgb
    ok, data = cv2.imencode(".png", pixels)
    if not ok:
        raise RuntimeError("PNG encoder failed")
    return data.tobytes()


def encode_frame(sequence, turn, phase, source_index, rgb, audio, jpeg=None, generated=False,
                 *, metadata=None, face=b"", mask=b""):
    if len(audio) > SAMPLES:
        raise ValueError("A video frame may contain at most 640 audio samples")
    if len(audio):
        pcm = np.zeros(SAMPLES, dtype="<i2")
        pcm[:len(audio)] = np.rint(np.clip(audio, -1, 32767 / 32768) * 32768).astype("<i2")
        payload = pcm.tobytes()
    else:
        payload = SILENCE
    if jpeg is None:
        jpeg = encode_image(rgb)
    description = json.dumps(metadata or {}, separators=(",", ":"), allow_nan=False).encode("utf-8")
    return HEADER.pack(MAGIC, sequence, turn, phase, source_index, SAMPLES,
                       len(jpeg), int(generated), len(description), len(face), len(mask)) + payload + description + jpeg + face + mask



def decode_frame(data):
    """Diagnostic decoder also checks framing and native-resolution transport."""
    if len(data) < HEADER.size:
        raise ValueError("Incomplete avatar media packet")
    magic, sequence, turn, phase, source_index, samples, size, generated, meta_size, face_size, mask_size = HEADER.unpack_from(data)
    if magic != MAGIC or samples != SAMPLES or len(data) != HEADER.size + samples * 2 + size + meta_size + face_size + mask_size:
        raise ValueError("Invalid avatar media packet")
    pcm = np.frombuffer(data, dtype="<i2", count=samples, offset=HEADER.size).copy()
    offset = HEADER.size + samples * 2
    metadata = json.loads(data[offset:offset + meta_size])
    offset += meta_size

    def decode_image(length, mode):
        nonlocal offset
        if not length:
            return None
        pixels = cv2.imdecode(np.frombuffer(data, dtype=np.uint8, count=length, offset=offset), mode)
        offset += length
        if pixels is None:
            raise ValueError("Invalid avatar image")
        return cv2.cvtColor(pixels, cv2.COLOR_BGR2RGB) if mode == cv2.IMREAD_COLOR else pixels

    rgb = decode_image(size, cv2.IMREAD_COLOR)
    face = decode_image(face_size, cv2.IMREAD_COLOR)
    mask = decode_image(mask_size, cv2.IMREAD_GRAYSCALE)
    return dict(sequence=sequence, turn=turn, phase=phase, source_index=source_index,
                pts=sequence / 25, pcm=pcm, rgb=rgb, generated=bool(generated), metadata=metadata, face=face, mask=mask)
