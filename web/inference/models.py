"""Load ordinary checkpoints or portable quantized TorchScript models on CUDA."""
import gc
from pathlib import Path
import time

import torch

MODEL_FILES = ("hubert_fp16.pt", "lip_fp32.pt")
QUANTIZED_MODEL_FILES = ("hubert_w8a16.pt", "lip_mixed_int8.pt")


def select_model_files(directory):
    """Select a complete pair; preserve ordinary models when both pairs exist."""
    directory = Path(directory)
    for files in (MODEL_FILES, QUANTIZED_MODEL_FILES):
        if all((directory / name).is_file() for name in files):
            return files
    raise FileNotFoundError(
        f"{directory} must contain either {' + '.join(MODEL_FILES)} "
        f"or {' + '.join(QUANTIZED_MODEL_FILES)}. Use --models PATH.")


class CUDAUnavailableError(RuntimeError):
    """A CUDA-enabled PyTorch installation and an NVIDIA GPU are required."""


class CUDAExecutionError(RuntimeError):
    """CUDA execution failed; close this engine before trying again."""


def require_cuda():
    if not torch.cuda.is_available():
        raise CUDAUnavailableError("Install CUDA-enabled PyTorch and a compatible NVIDIA driver")
    try:
        with torch.inference_mode():
            value = torch.eye(8, device="cuda:0")
            if not torch.equal(value @ value, value):
                raise CUDAUnavailableError("CUDA initialization computation failed")
            torch.cuda.synchronize()
    except Exception as error:
        raise CUDAUnavailableError("NVIDIA CUDA initialization failed") from error


class Models:
    def __init__(self, directory):
        self.hubert = self.lip = None
        self.load_timings = {}
        self.device = torch.device("cuda:0")
        directory = Path(directory).expanduser().resolve()
        files = select_model_files(directory)
        self.quantized = files == QUANTIZED_MODEL_FILES
        try:
            require_cuda()
            torch.set_num_threads(4)
            torch.backends.cuda.matmul.allow_tf32 = False
            torch.backends.cudnn.allow_tf32 = False
            for name, filename in zip(("hubert", "lip"), files):
                started = time.perf_counter()
                if self.quantized:
                    model = torch.jit.load(str(directory / filename), map_location=self.device).eval()
                    setattr(self, name, model)
                    self.load_timings[name + "_load_to_gpu"] = (time.perf_counter() - started) * 1000
                    del model
                    continue
                data = torch.load(directory / filename, map_location="cpu", weights_only=True, mmap=True)
                if name == "hubert":
                    from transformers import HubertConfig, HubertModel
                    with torch.device("meta"):
                        model = HubertModel(HubertConfig.from_dict(data["config"]))
                    weights = data["state_dict"]
                    dtype = torch.float16
                else:
                    from .networks import LiveTalkingModel
                    cfg = {f"down.{i}": dict(in_channels=10, out_channels=10, kernel_size=3,
                           stride=2 if i == 2 else 1, padding=1, dilation=1) for i in range(3)}
                    with torch.device("meta"):
                        model = LiveTalkingModel(cfg, (3, 3, 3))
                    weights = data["state_dict"]
                    dtype = torch.float32
                model.load_state_dict(weights, strict=True, assign=True)
                setattr(self, name, model.eval().to(device=self.device, dtype=dtype))
                self.load_timings[name + "_load_to_gpu"] = (time.perf_counter() - started) * 1000
                del data, weights, model
            self.parameter_mib = {name: sum(p.numel() * p.element_size() for p in
                (getattr(self, name).state_dict().values() if self.quantized else
                 getattr(self, name).parameters())) / 1024**2 for name in ("hubert", "lip")}
            gc.collect()
            torch.cuda.empty_cache()
        except Exception:
            self.close()
            raise

    @torch.inference_mode()
    def run(self, name, inputs):
        try:
            if name == "hubert":
                output = self.hubert(inputs["pcm"])
                return (output if self.quantized else output.last_hidden_state)[0].float()
            if name == "audio":
                if self.quantized:
                    return self.lip.audio(inputs["windows"], inputs["h"], inputs["c"], inputs["start_frame"])
                return self.lip.audio_encoder.forward_sequence(inputs["windows"], inputs["h"],
                    inputs["c"], inputs["start_frame"], 10)
            if name == "face":
                if self.quantized:
                    return self.lip.face(inputs["image"])
                return self.lip.face_encoder(inputs["image"])
            if name == "generator":
                if self.quantized:
                    tensors = inputs["tensors"]
                    # The engine uses generator argument order; TorchScript accepts face() order.
                    return self.lip.generate(tensors[0], list(reversed(tensors[1:])))
                return self.lip.generator(*inputs["tensors"])
            raise ValueError("Unknown internal model component")
        except Exception as error:
            self.close()
            raise CUDAExecutionError(f"{name}: CUDA execution failed; no CPU fallback was attempted") from error

    def memory(self, reset_peak=False):
        torch.cuda.synchronize(self.device)
        if reset_peak:
            torch.cuda.reset_peak_memory_stats(self.device)
        return {"allocated_mib": torch.cuda.memory_allocated(self.device) / 1024**2,
                "reserved_mib": torch.cuda.memory_reserved(self.device) / 1024**2,
                "peak_allocated_mib": torch.cuda.max_memory_allocated(self.device) / 1024**2,
                "peak_reserved_mib": torch.cuda.max_memory_reserved(self.device) / 1024**2,
                "parameter_mib": dict(self.parameter_mib)}

    def close(self):
        self.hubert = self.lip = None
        gc.collect()
        if torch.cuda.is_initialized():
            torch.cuda.empty_cache()
