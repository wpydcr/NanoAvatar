<div align="center">
<h1>📱 NanoAvatar</h1>
<h3>High-definition, high-fidelity talking avatars on your phone.</h3>
<p>📱 <strong>32 FPS on a 2023 Android chipset</strong> · ⚡ <strong>127 ms model first frame</strong> · 🏠 <strong>On-device avatar inference</strong></p>
<p>Generate talking avatars on your phone without a cloud GPU.</p>
<p>
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-1.0.0.apk"><img src="https://img.shields.io/badge/Android-Download_APK-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="Download Android APK"></a>
<a href="https://huggingface.co/wpydcr/NanoAvatar"><img src="https://img.shields.io/badge/Hugging_Face-Model_Weights-FFD21E?style=for-the-badge" alt="Model weights on Hugging Face"></a>
<a href="#quick-start"><img src="https://img.shields.io/badge/Quick_Start-Get_Running-2563EB?style=for-the-badge" alt="Quick start"></a>
</p>
<p>
<a href="#demo">🎬 Demos</a> ·
<a href="#performance">📊 Benchmarks</a> ·
English · <a href="README.zh-CN.md">简体中文</a>
</p>
</div>

🌊 **Streaming generation: start speaking as audio arrives.** With a streaming LLM and streaming TTS, NanoAvatar starts speaking in **about 0.3 seconds in our tests**, without waiting for the complete audio.

<a id="demo"></a>
## 🎬 See it in action

Watch the English and Chinese demos to see the lip sync and visual detail.

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

<a id="performance"></a>
## 📊 32 FPS on a phone. 333 FPS on desktop.

Model benchmarks reported on the [model page](https://huggingface.co/wpydcr/NanoAvatar):

| Model | Device | Model throughput | Model first frame | Memory / VRAM |
| --- | --- | ---: | ---: | ---: |
| 📱 Android compiled | **Z60 Ultra · Snapdragon 8 Gen 3**<br>Android 14 | **32 FPS** | **127 ms** | **785 MiB** |
| ⚡ Quantized | **RTX 4090**<br>Windows CUDA | **333 FPS** | **18 ms** | **834 MiB** |
| 🖥️ Full precision | **RTX 4090**<br>Windows CUDA | **224 FPS** | **37 ms** | **1119 MiB** |

Android first-frame timing runs from audio feature extraction to the first generated image. The app plays at **25 FPS** for audio synchronization.

The model generates a **256 × 256 face region**, composited at the source video's original resolution.

<a id="quick-start"></a>
## 🚀 Try NanoAvatar

**Start with the Android APK**, or use the [Web demo](#run-on-web) on your NVIDIA GPU.

<a id="run-on-android"></a>
### 📱 Android: install and start talking

**Requirements:** Android 12+, ARM64, Snapdragon 8 Gen 3 / HTP v75.

1. 📦 **[Download NanoAvatar-1.0.0.apk](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-1.0.0.apk)** and install it on your phone.
2. Open the app and let it prepare the bundled models and default avatar. No computer or manual file copying is needed.
3. Add your **DashScope API key** in Settings to start chatting with cloud-based Qwen and CosyVoice.

Release notes and all downloads are on the [Releases page](https://github.com/wpydcr/NanoAvatar/releases).

<a id="run-on-web"></a>
### 🖥️ Web: run on your NVIDIA GPU

**Requirements:** Python 3.11, an NVIDIA GPU and CUDA PyTorch.

Get the source:

```shell
git clone https://github.com/wpydcr/NanoAvatar.git
cd NanoAvatar
```

Download [NanoAvatar-avatar.zip](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip) and place its extracted `avatar/` directory at `avatars/person/avatar/` inside the repository. Install the dependencies:

```shell
python -m pip install --force-reinstall torch==2.10.0 --index-url https://download.pytorch.org/whl/cu128
python -m pip install -r web/requirements.txt
python -m pip install huggingface_hub
```

Choose a model to download and run:

**🖥️ Full precision**

```shell
hf download wpydcr/NanoAvatar --include "full-precision/*" --local-dir models
python web/run.py --models models/full-precision --avatar avatars/person/avatar
```

**⚡ Quantized: 333 FPS on Windows / RTX 4090**

```shell
hf download wpydcr/NanoAvatar --include "quantized/*" --local-dir models
python web/run.py --models models/quantized --avatar avatars/person/avatar
```

The Web runtime selects the model from `--models`. The quantized CUDA DLL is bundled with the Web code.

Open **http://127.0.0.1:8765**. Startup loads the models and prepares the avatar.

The reinstall option replaces a same-version CPU wheel that may already be installed in your environment.

- **Local WAV:** 16 kHz, mono, PCM16, up to 90 seconds. No cloud account is required.
- **Text conversation:** enter a DashScope API key in Settings, or set `DASHSCOPE_API_KEY` for the server process. The example uses Qwen and CosyVoice; edit `web/cloud.py` to change services.
- **Stop, clear, reconnect:** stop the active response, clear the conversation, or start a new connection. Clearing retains the input draft.

## 🛠️ Models and source builds

This repository contains inference source code. Weights are hosted on Hugging Face; the APK and avatar package are hosted in Releases.

| Platform | Source | Hugging Face weights |
| --- | --- | --- |
| **Android** | [`android/`](android/) | [`android-qnn/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/android-qnn) |
| **Web, full precision** | [`web/`](web/) | [`full-precision/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/full-precision) |
| **Web, quantized** | [`web/`](web/) | [`quantized/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/quantized) |

<details>
<summary><strong>🧠 Which model package should I use?</strong></summary>

Web directly loads PyTorch checkpoints. Choose `full-precision/` for HuBERT FP16 and the lip-sync network FP32, or `quantized/` for HuBERT W8A16 and mixed INT8 lip-sync inference. The bundled quantized runtime targets Windows CUDA SM89 (RTX 4090).

Android loads `android-qnn/`: ONNX graphs and QNN contexts with W8A16 HuBERT, FP32 CPU face/audio encoders, and an HTP FP16 generator.

</details>

<details>
<summary><strong>🔧 Build the Android APK from source</strong></summary>

Start in the cloned repository root. Install JDK 17 or newer and Android SDK Platform 35 / Build Tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`. Download [`android-qnn/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/android-qnn) from Hugging Face and place its complete contents under `bundle-assets/bundled/payload/`. Extract [NanoAvatar-avatar.zip](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip) into `bundle-assets/bundled/` to get this layout:

```text
bundle-assets/bundled/
  payload/phone_config.json
  payload/models/
  payload/quality/
  avatar/avatar.json
  avatar/...
```

Keep this resource directory out of the source repository. Then build the APK with its resources:

```shell
cd android
sh gradlew :app:assembleRelease -PbundleAssets=../bundle-assets
```

On Windows use `.\gradlew.bat :app:assembleRelease "-PbundleAssets=../bundle-assets"`. The APK is written to `android/app/build/outputs/apk/release/app-release.apk`.

</details>

## ⭐ Support NanoAvatar

If NanoAvatar is useful to you, **give it a star**. Share what you build or report a problem in [Issues](https://github.com/wpydcr/NanoAvatar/issues). For performance reports, include your device, OS version and model package.

<a id="license"></a>
## 📄 License

- **MIT**: NanoAvatar-authored source code; Chinese HuBERT weights and their converted variants retain the upstream MIT license.
- **CC BY-NC 4.0**: NanoAvatar lip-sync weights and their quantized or compiled variants, the default avatar package, and demo videos.
- **Third-party components**: retain their original licenses.

See [LICENSE](LICENSE) for the scope and full terms. These licenses apply to different materials; they are not a choice between MIT and CC BY-NC 4.0 for the same file.

For commercial licensing, contact [wupingyu@mail.ustc.edu.cn](mailto:wupingyu@mail.ustc.edu.cn).
