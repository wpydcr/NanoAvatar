<div align="center">
<h1>NanoAvatar</h1>
<p><strong>A lightweight model for real-time interactive avatars.</strong></p>
<p>Measured on Snapdragon 8 Gen 3: <strong>32 FPS</strong> model throughput · First-frame latency as low as <strong>0.13 seconds</strong></p>
<p>English · <a href="README.zh-CN.md">简体中文</a></p>
<p>
<a href="#demo">Demos</a> ·
<a href="#quick-start">Quick start</a> ·
<a href="https://huggingface.co/wpydcr/NanoAvatar">Model weights</a> ·
<a href="https://github.com/wpydcr/NanoAvatar/releases">Android APK</a> ·
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip">Avatar package</a>
</p>
</div>

Generating high-definition video in real time on edge devices reduces avatar applications’ reliance on cloud GPU servers. NanoAvatar can also substantially reduce compute requirements in cloud deployments.

<a id="demo"></a>
## Demos

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

<a id="quick-start"></a>
## Quick start

| Platform | Source | Hugging Face weights |
| --- | --- | --- |
| **Web** | [`web/`](web/) | [`full-precision/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/full-precision) |
| **Android** | [`android/`](android/) | [`android-qnn/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/android-qnn) |

- **Web requirements:** Python 3.11, NVIDIA GPU, CUDA PyTorch.
- **Android requirements:** Android 12+, ARM64; supplied QNN models target Snapdragon 8 Gen 3 / HTP v75.

This repository contains inference source code. Model weights are available on Hugging Face; the Android APK and avatar package are available through Releases. Web directly loads PyTorch checkpoints, while Android directly loads ONNX graphs and QNN contexts.

Download [NanoAvatar-avatar.zip](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip), then place its extracted `avatar/` directory at `avatars/person/avatar/` for Web or `bundle-assets/bundled/avatar/` for Android source builds; the APK already includes the same avatar package and needs no separate download.

<details>
<summary>Model variants</summary>

The full-precision package contains HuBERT FP16 and the lip-sync network FP32. Android uses W8A16 HuBERT, FP32 CPU face/audio encoders, and an HTP FP16 generator. The separate `quantized/` directory on Hugging Face contains PyTorch/CUDA checkpoints and is **not the Android package**. Other phones require compatible QNN models and runtime support.

</details>

All commands below start in the repository root unless stated otherwise.

## Run on Web

Install CUDA PyTorch and the Web dependencies:

```shell
python -m pip install --force-reinstall torch==2.10.0 --index-url https://download.pytorch.org/whl/cu128
python -m pip install -r web/requirements.txt
python -m pip install huggingface_hub
hf download wpydcr/NanoAvatar --include "full-precision/*" --local-dir models
python web/run.py --models models/full-precision --avatar avatars/person/avatar
```

Open **http://127.0.0.1:8765**. Startup loads the models and prepares the avatar. A working NVIDIA CUDA device is required; the server reports an error if CUDA is unavailable.

The reinstall option replaces a same-version CPU wheel that may already be installed in your environment.

- **Local WAV:** 16 kHz, mono, PCM16, up to 90 seconds. No cloud account is required.
- **Text conversation:** enter a DashScope API key in Settings, or set `DASHSCOPE_API_KEY` for the server process. The example uses Qwen and CosyVoice; edit `web/cloud.py` to change services.
- **Stop, clear, reconnect:** stop the active response, clear the conversation, or start a new connection. Clearing retains the input draft.

## Run on Android

Download **NanoAvatar-1.0.0.apk** from [Releases](https://github.com/wpydcr/NanoAvatar/releases) and install it directly on your phone. The APK includes the models and a default avatar. The first launch prepares these resources automatically, with no computer or manual file copying required. Once ready, enter your own DashScope key in Settings to use the conversation feature.

Android currently uses a fixed **25 FPS** timeline to stay synchronized with the audio stream. When adapting the code for older chipsets, this can be reduced to **12.5 FPS**, reusing each generated image for two frames while retaining a fluid experience.

<details>
<summary><strong>Build the Android APK from source</strong></summary>

Install JDK 17 or newer and Android SDK Platform 35 / Build Tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`. Download `android-qnn/` from Hugging Face and place its complete contents under `payload/`; place the avatar package under `avatar/` as described above:

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

## Performance

Model benchmarks reported on the [model page](https://huggingface.co/wpydcr/NanoAvatar):

| Model | Device | Throughput | First-frame latency | Memory / VRAM |
| --- | --- | ---: | ---: | ---: |
| Android compiled | Z60 Ultra · Snapdragon 8 Gen 3<br>Android 14 | **32 FPS** | **127 ms** | 785 MiB |
| Full precision | RTX 4090<br>Windows CUDA | **224 FPS** | **37 ms** | 1119 MiB |

The model generates a **256 × 256** face region, composited at the source video's original resolution.

## License

- **MIT**: NanoAvatar-authored source code; Chinese HuBERT weights and their converted variants retain the upstream MIT license.
- **CC BY-NC 4.0**: NanoAvatar lip-sync weights and their quantized or compiled variants, the default avatar package, and demo videos.
- **Third-party components**: retain their original licenses.

See [LICENSE](LICENSE) for the scope and full terms. These licenses apply to different materials; they are not a choice between MIT and CC BY-NC 4.0 for the same file.

For commercial licensing, contact [wupingyu@mail.ustc.edu.cn](mailto:wupingyu@mail.ustc.edu.cn).
