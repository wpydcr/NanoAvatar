<div align="center">
<h1>📱 NanoAvatar</h1>
<h3>高清高保真数字人，在你的手机上实时开口说话。</h3>
<p>📱 <strong>2023 款安卓芯片实测 32 FPS</strong> · ⚡ <strong>模型首帧 127 ms</strong> · 🏠 <strong>数字人本地推理</strong></p>
<p>直接在手机上生成数字人视频，无需云端 GPU。</p>
<p>
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-1.0.0.apk"><img src="https://img.shields.io/badge/Android-下载_APK-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="下载安卓 APK"></a>
<a href="https://huggingface.co/wpydcr/NanoAvatar"><img src="https://img.shields.io/badge/Hugging_Face-模型权重-FFD21E?style=for-the-badge" alt="Hugging Face 模型权重"></a>
<a href="#quick-start"><img src="https://img.shields.io/badge/Quick_Start-开始体验-2563EB?style=for-the-badge" alt="快速开始"></a>
</p>
<p>
<a href="#demo">🎬 效果演示</a> ·
<a href="#performance">📊 性能实测</a> ·
<a href="README.md">English</a> · 简体中文
</p>
</div>

🌊 **流式生成，边收音频边开口。** 接入流式大模型和流式 TTS，实测约 **0.3 秒开始说话**，无需等待整段音频生成完成。

<a id="demo"></a>
## 🎬 看看实际效果

通过中英文演示，直接查看口型同步和画面细节。

<table align="center">
  <tr>
    <th align="center" width="50%">中文演示</th>
    <th align="center" width="50%">英文演示</th>
  </tr>
  <tr>
    <td align="center"><video src="https://github.com/user-attachments/assets/0518c0e2-22b2-43a8-926a-ba37b2e6c7b7" controls playsinline preload="metadata" width="280"></video></td>
    <td align="center"><video src="https://github.com/user-attachments/assets/5d2b7f12-9557-42a9-aea8-d6f68fa4798e" controls playsinline preload="metadata" width="280"></video></td>
  </tr>
</table>

<a id="performance"></a>
## 📊 手机上的 32 FPS，桌面上的 333 FPS

以下为[模型页](https://huggingface.co/wpydcr/NanoAvatar)提供的实测模型基准：

| 模型 | 设备 | 模型吞吐量 | 模型首帧 | 内存 / 显存 |
| --- | --- | ---: | ---: | ---: |
| 📱 安卓编译版 | **努比亚 Z60 Ultra · 骁龙 8 Gen 3**<br>Android 14 | **32 FPS** | **127 ms** | **785 MiB** |
| ⚡ 量化版 | **RTX 4090**<br>Windows CUDA | **333 FPS** | **18 ms** | **834 MiB** |
| 🖥️ 满血版 | **RTX 4090**<br>Windows CUDA | **224 FPS** | **37 ms** | **1119 MiB** |

安卓首帧统计从音频特征提取到首张图像生成的耗时。App 以 **25 FPS** 播放，保持音画同步。

模型生成 **256 × 256 的人脸区域**，最终画面按原视频分辨率合成。

<a id="quick-start"></a>
## 🚀 立即体验

优先安装 **安卓 APK**，或用自己的 NVIDIA GPU 运行 [Web 演示](#run-on-web)。

<a id="run-on-android"></a>
### 📱 安卓：安装后开始对话

**环境要求：** Android 12 及以上、ARM64、骁龙 8 Gen 3 / HTP v75。

1. 📦 **[下载 NanoAvatar-1.0.0.apk](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-1.0.0.apk)**，直接安装到手机。
2. 打开 App，等待它自动准备内置模型和默认人物，无需电脑或手动复制文件。
3. 在设置中填写 **DashScope API Key**，接入云端通义千问和 CosyVoice，开始对话。

版本说明和全部下载文件见 [Releases](https://github.com/wpydcr/NanoAvatar/releases)。

<a id="run-on-web"></a>
### 🖥️ Web：用本地 NVIDIA GPU 运行

**环境要求：** Python 3.11、NVIDIA GPU、CUDA 版 PyTorch。

获取源码：

```shell
git clone https://github.com/wpydcr/NanoAvatar.git
cd NanoAvatar
```

下载 [NanoAvatar-avatar.zip](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip)，将解压出的 `avatar/` 目录放到仓库内的 `avatars/person/avatar/`。安装依赖：

```shell
python -m pip install --force-reinstall torch==2.10.0 --index-url https://download.pytorch.org/whl/cu128
python -m pip install -r web/requirements.txt
python -m pip install huggingface_hub
```

选择一个版本下载并启动：

**🖥️ 满血版**

```shell
hf download wpydcr/NanoAvatar --include "full-precision/*" --local-dir models
python web/run.py --models models/full-precision --avatar avatars/person/avatar
```

**⚡ 量化版：Windows / RTX 4090 上 333 FPS**

```shell
hf download wpydcr/NanoAvatar --include "quantized/*" --local-dir models
python web/run.py --models models/quantized --avatar avatars/person/avatar
```

Web 会根据 `--models` 指向的目录选择模型，量化 CUDA DLL 已随 Web 代码提供。

打开 **http://127.0.0.1:8765**。启动时会加载模型并准备人物。

重装参数用于替换环境中可能已有的同版本 CPU 版 PyTorch。

- **本地 WAV**：16 kHz、单声道、PCM16，最长 90 秒，无需云端账号。
- **文字对话**：在设置中填写 DashScope API Key，或给服务进程设置 `DASHSCOPE_API_KEY`。示例使用通义千问和 CosyVoice，可修改 `web/cloud.py` 更换服务。
- **停止、清空、重连**：停止当前回答、清空对话或建立新连接；清空时保留输入草稿。

## 🛠️ 模型与源码构建

本仓库提供推理源码。模型权重放在 Hugging Face，APK 和人物包放在 Releases。

| 平台 | 源码 | Hugging Face 权重 |
| --- | --- | --- |
| **安卓** | [`android/`](android/) | [`android-qnn/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/android-qnn) |
| **Web 满血版** | [`web/`](web/) | [`full-precision/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/full-precision) |
| **Web 量化版** | [`web/`](web/) | [`quantized/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/quantized) |

<details>
<summary><strong>🧠 应该选择哪个模型包？</strong></summary>

Web 直接加载 PyTorch 权重：`full-precision/` 包含 HuBERT FP16 和口型网络 FP32；`quantized/` 包含 HuBERT W8A16 和混合 INT8 口型模型。附带的量化运行时面向 Windows CUDA SM89（RTX 4090）。

安卓使用 `android-qnn/` 中的 ONNX 模型和 QNN 上下文，包括 W8A16 HuBERT、FP32 CPU 人脸与音频编码器、HTP FP16 生成器。

</details>

<details>
<summary><strong>🔧 从源码构建安卓 APK</strong></summary>

从已克隆的仓库根目录开始。安装 JDK 17 或更新版本，以及 Android SDK Platform 35、Build Tools 35.0.0，设置 `JAVA_HOME` 和 `ANDROID_HOME`。从 HF 下载 [`android-qnn/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/android-qnn)，将其完整内容放到 `bundle-assets/bundled/payload/`。将 [NanoAvatar-avatar.zip](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip) 解压到 `bundle-assets/bundled/`，得到以下结构：

```text
bundle-assets/bundled/
  payload/phone_config.json
  payload/models/
  payload/quality/
  avatar/avatar.json
  avatar/...
```

资源目录不提交到源码仓库。然后构建包含资源的 APK：

```shell
cd android
sh gradlew :app:assembleRelease -PbundleAssets=../bundle-assets
```

Windows 使用 `.\gradlew.bat :app:assembleRelease "-PbundleAssets=../bundle-assets"`。APK 输出到 `android/app/build/outputs/apk/release/app-release.apk`。

</details>

## ⭐ 支持 NanoAvatar

如果 NanoAvatar 对你有帮助，欢迎 **点一个 Star**。也欢迎在 [Issues](https://github.com/wpydcr/NanoAvatar/issues) 分享你的作品或反馈问题。反馈性能时，请附上设备、系统版本和模型包。

<a id="license"></a>
## 📄 许可

- **MIT**：NanoAvatar 自有源码；Chinese HuBERT 权重及其转换版本保留上游 MIT 许可。
- **CC BY-NC 4.0**：NanoAvatar 口型权重及其量化、编译版本、默认人物包和演示视频。
- **第三方组件**：保留各自原有许可。

各部分的适用范围及完整协议见 [LICENSE](LICENSE)。这些协议分别适用于不同内容，并非同一文件可任选 MIT 或 CC BY-NC 4.0。

商业授权请联系 [wupingyu@mail.ustc.edu.cn](mailto:wupingyu@mail.ustc.edu.cn)。
