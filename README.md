<div align="center">
<h1>📱 NanoAvatar</h1>
<h3>Changing how often avatar applications can be used and how many users they can serve</h3>
<p>📱 <strong>2023 mobile chipset · 41 FPS</strong> · ⚡ <strong>103 ms first-frame latency</strong></p>
<p>
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar.apk"><img src="https://img.shields.io/badge/Android-Full_APK-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Download Full APK"></a>
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-Lite.apk"><img src="https://img.shields.io/badge/Android-Lite_APK-0EA5E9?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Download Lite APK"></a>
<a href="https://huggingface.co/wpydcr/NanoAvatar"><img src="https://img.shields.io/badge/Hugging_Face-Model_Weights-FFD21E?style=for-the-badge" alt="Model weights on Hugging Face"></a>
</p>
<p>
<a href="#demo">🎬 Demos</a> ·
<a href="#quick-start">Try it</a> ·
<a href="#performance">📊 Benchmarks</a> ·
English · <a href="https://github.com/wpydcr/NanoAvatar/blob/main/README.zh-CN.md">简体中文</a>
</p>
</div>

**Generate high-fidelity talking-avatar videos directly on your phone, without a cloud GPU.**

<a id="demo"></a>
## 🎬 Demos

> **Generalization demo:** The examples below were not used to train the model, nor was it specifically fine-tuned for them.

<table align="center">
  <tr>
    <th align="center" width="50%">English demo</th>
    <th align="center" width="50%">Chinese demo</th>
  </tr>
  <tr>
    <td align="center"><video src="https://github.com/user-attachments/assets/5d2b7f12-9557-42a9-aea8-d6f68fa4798e" controls playsinline preload="metadata" width="280"></video></td>
    <td align="center"><video src="https://github.com/user-attachments/assets/0518c0e2-22b2-43a8-926a-ba37b2e6c7b7" controls playsinline preload="metadata" width="280"></video></td>
  </tr>
</table>

**Try the on-device avatar generation model offline.**

<a id="quick-start"></a>
<a id="run-on-android"></a>
## 🚀 Try it on your phone

**Version 1.0.0** · [Full APK](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar.apk) · [Lite APK](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-Lite.apk)

1. Install an APK and open the app.
2. In **Experience** mode, hold the record button, speak, then release to animate the avatar with your own voice.
3. For AI conversation, switch to **Conversation** mode and enter an Alibaba Cloud API key.

- **Full:** generation is fixed at **25 FPS**.
- **Lite:** lightweight models with generation fixed at **12.5 FPS**, a smaller download and lower compute requirements.

Both versions display FPS and first-frame time in real time.

<a id="performance"></a>
## 📊 Performance

| Model | Device | Model FPS | First frame | Memory / VRAM estimate |
| --- | --- | ---: | ---: | ---: |
| 📱 NanoAvatar | 2023 · Snapdragon 8 Gen 3 | **39 FPS** | **115 ms** | **834 MiB** |
| 📱 NanoAvatar Lite | 2023 · Snapdragon 8 Gen 3 | **41 FPS** | **103 ms** | **700 MiB** |
| 📱 NanoAvatar Lite | 2021 · Snapdragon 8 Gen 1 | **18 FPS** | **183 ms** | **693 MiB** |
| ⚡ Quantized | RTX 4090 · Windows CUDA | **333 FPS** | **18 ms** | **834 MiB** |
| 🖥️ Full precision | RTX 4090 · Windows CUDA | **224 FPS** | **37 ms** | **1119 MiB** |

🌊 **Streaming generation: start speaking as audio arrives.** With a streaming LLM and streaming TTS, NanoAvatar starts speaking in **about 0.3 seconds in our tests**, without waiting for the complete audio.

<a id="run-on-web"></a>
## 🖥️ Web: run on your NVIDIA GPU

The Web code is a simple demo: upload a video to try it. For the best results in actual use, prepare an avatar package.

**Requirements:** Python 3.11, an NVIDIA GPU and CUDA PyTorch.

Get the source:

```shell
git clone https://github.com/wpydcr/NanoAvatar.git
cd NanoAvatar
```

Install the dependencies:

```shell
python -m pip install --force-reinstall torch==2.10.0 --index-url https://download.pytorch.org/whl/cu128
python -m pip install -r web/requirements.txt
python -m pip install huggingface_hub
```

Choose a model to download and run:

**🖥️ Full precision**

HuBERT FP16 and the lip-sync network in FP32.

```shell
hf download wpydcr/NanoAvatar --include "full-precision/*" --local-dir models
python web/run.py --models models/full-precision
```

**⚡ Quantized (Windows / RTX 4090)**

HuBERT W8A16 and mixed INT8 lip-sync inference.

```shell
hf download wpydcr/NanoAvatar --include "quantized/*" --local-dir models
python web/run.py --models models/quantized
```

The Web runtime selects the model from `--models`. The quantized CUDA DLL is bundled with the Web code.

Open **http://127.0.0.1:8890**. Upload a short, continuous video of one person facing the camera, wait for preparation to finish, then upload audio to animate the person. Videos can be up to 256 MB; the demo uses the first 10 seconds by default.

The reinstall option replaces a same-version CPU wheel that may already be installed in your environment.

- **Local audio:** WAV, MP3 and other formats your browser can decode, up to 32 MB and 90 seconds. No cloud account is required.
- **Text conversation:** enter a DashScope API key under Text Conversation Settings and click Apply Settings, or set `DASHSCOPE_API_KEY` for the server process. The example uses Qwen and CosyVoice; edit `web/cloud.py` to change services.
- **Stop, reconnect:** stop the active response or reconnect after a disconnection.

## 🛠️ Models and source

This repository contains Web inference source code. Weights are hosted on Hugging Face; the APKs are hosted in Releases.

| Platform | Source | Hugging Face weights |
| --- | --- | --- |
| **Web, full precision** | [`web/`](web/) | `full-precision/`（come soon） |
| **Web, quantized** | [`web/`](web/) | [`quantized/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/quantized) |

[`android/`](android/) contains the APK source code.

## ⭐ Support NanoAvatar

If NanoAvatar is useful to you, **give it a star**. Share what you build or report a problem in [Issues](https://github.com/wpydcr/NanoAvatar/issues). For performance reports, include your device, OS version and model package.

<a id="license"></a>
## 📄 License

- **MIT**: NanoAvatar-authored source code; Chinese HuBERT weights and their converted variants retain the upstream MIT license.
- **CC BY-NC 4.0**: NanoAvatar lip-sync weights and their quantized or compiled variants, and demo videos.
- **Third-party components**: retain their original licenses.

See [LICENSE](LICENSE) for the scope and full terms. These licenses apply to different materials; they are not a choice between MIT and CC BY-NC 4.0 for the same file.

For commercial licensing, contact [wupingyu@mail.ustc.edu.cn](mailto:wupingyu@mail.ustc.edu.cn).
