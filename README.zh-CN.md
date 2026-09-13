<div align="center">
<h1>📱 NanoAvatar</h1>
<h3>改变了数字人应用的使用频率和规模上限</h3>
<p>📱 <strong>骁龙 8 Gen 3 · Lite 37 FPS</strong> · ⚡ <strong>模型首帧 112 ms</strong></p>
<p>
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar.apk"><img src="https://img.shields.io/badge/Android-完整版_APK-3DDC84?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="下载完整版 APK"></a>
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-Lite.apk"><img src="https://img.shields.io/badge/Android-Lite_APK-0EA5E9?style=for-the-badge&amp;logo=android&amp;logoColor=white" alt="下载 Lite APK"></a>
<a href="https://huggingface.co/wpydcr/NanoAvatar"><img src="https://img.shields.io/badge/Hugging_Face-模型权重-FFD21E?style=for-the-badge" alt="Hugging Face 模型权重"></a>
</p>
<p>
<a href="#demo">🎬 效果演示</a> ·
<a href="#quick-start">开始体验</a> ·
<a href="#performance">📊 性能实测</a> ·
<a href="https://github.com/wpydcr/NanoAvatar">English</a> · 简体中文
</p>
</div>

**无需 API key，断网也能用自己的声音驱动数字人。**

<a id="demo"></a>
## 🎬 实际效果

> **泛化能力展示：** 以下演示案例未用于模型训练，模型也未针对这些案例进行专门微调。

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

**直接在手机上生成高保真数字人视频，无需云端 GPU。**

<a id="quick-start"></a>
<a id="run-on-android"></a>
## 🚀 手机上体验

**版本 1.0.0** · [完整版 APK](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar.apk) · [Lite APK](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-Lite.apk)

1. 安装 APK 并打开 App，模型和默认人物已经内置。
2. 默认进入**体验模式**。长按录音按钮说话，松开后用自己的声音驱动人物。
3. 如需 AI 对话，切换到**交互模式**并填写阿里云 API key。

- **完整版**：完整人物资源，生成帧率固定为 **25 FPS**。
- **Lite**：轻量化模型，内置 **3 秒人物素材**，循环使用并随音频持续生成。生成帧率固定为 **12.5 FPS**，安装包更小，算力需求更低。

两版都会实时显示 FPS 和首帧时间。

<a id="performance"></a>
## 📊 性能实测

| 模型 | 设备 | 最佳模型 FPS | 最佳首帧 | 内存 / 显存估算 |
| --- | --- | ---: | ---: | ---: |
| 📱 NanoAvatar 完整版 | 努比亚 Z60 Ultra · 骁龙 8 Gen 3 | **35 FPS** | **127 ms** | **834 MiB** |
| 📱 NanoAvatar Lite | 努比亚 Z60 Ultra · 骁龙 8 Gen 3 | **37 FPS** | **112 ms** | **700 MiB** |
| ⚡ 量化版 | RTX 4090 · Windows CUDA | **333 FPS** | **18 ms** | **834 MiB** |
| 🖥️ 满血版 | RTX 4090 · Windows CUDA | **224 FPS** | **37 ms** | **1119 MiB** |

🌊 **流式生成，边收音频边开口。** 接入流式大模型和流式 TTS，实测约 **0.3 秒开始说话**，无需等待整段音频生成完成。

<a id="run-on-web"></a>
## 🖥️ Web：用本地 NVIDIA GPU 运行

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

HuBERT FP16，口型网络 FP32。

```shell
hf download wpydcr/NanoAvatar --include "full-precision/*" --local-dir models
python web/run.py --models models/full-precision --avatar avatars/person/avatar
```

**⚡ 量化版（Windows / RTX 4090）**

HuBERT W8A16，混合 INT8 口型模型。

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
| **Web 满血版** | [`web/`](web/) | `full-precision/`（come soon） |
| **Web 量化版** | [`web/`](web/) | [`quantized/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/quantized) |

<details>
<summary><strong>🔧 一套源码构建两版安卓 APK</strong></summary>

使用 JDK 17 或更新版本、Android SDK Platform 35 和 Build Tools 35.0.0，设置 `JAVA_HOME` 与 `ANDROID_HOME`。准备 Full 和 Lite 各自的资源目录，每份目录均包含 `bundled/payload/phone_config.json` 和 `bundled/avatar/avatar.json`。Release APK 的 `assets/bundled/` 中也包含对应资源。

在 `android/` 目录一次构建两版：

```shell
sh gradlew :app:assembleFullRelease :app:assembleLiteRelease -PfullBundleAssets=../bundle-assets/full -PliteBundleAssets=../bundle-assets/lite
```

Windows 将 `sh gradlew` 换为 `.\gradlew.bat`。两版均使用 `app/src/main/`，由 flavor 选择模型、人物、图标和帧率。版本统一为 **1.0.0**，发行文件名固定为 **NanoAvatar.apk** 与 **NanoAvatar-Lite.apk**。

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
