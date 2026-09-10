<div align="center">
<h1>NanoAvatar</h1>
<p><strong>面向实时交互数字人的轻量级模型。</strong></p>
<p>骁龙 8 Gen 3 实测模型吞吐 <strong>32 FPS</strong> · 首帧延迟低至 <strong>0.13 秒</strong></p>
<p><a href="README.md">English</a> · 简体中文</p>
<p>
<a href="#demo">演示</a> ·
<a href="#quick-start">开始使用</a> ·
<a href="https://huggingface.co/wpydcr/NanoAvatar">模型权重</a> ·
<a href="https://github.com/wpydcr/NanoAvatar/releases">安卓 APK</a> ·
<a href="https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip">人物包</a>
</p>
</div>

通过端侧实时生成高清视频，能够减少数字人应用对云端 GPU 服务器的依赖。即使是云端应用，也能大幅减少算力消耗。

<a id="demo"></a>
## 演示

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

<a id="quick-start"></a>
## 开始使用

| 平台 | 源码 | Hugging Face 权重 |
| --- | --- | --- |
| **Web** | [`web/`](web/) | [`full-precision/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/full-precision) |
| **安卓** | [`android/`](android/) | [`android-qnn/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/android-qnn) |

- **Web 环境**：Python 3.11、NVIDIA GPU、CUDA 版 PyTorch。
- **安卓环境**：Android 12 及以上、ARM64；所提供 QNN 模型面向骁龙 8 Gen 3 / HTP v75。

本仓库提供推理源码，模型权重从 Hugging Face 下载，安卓安装包和人物包从 Releases 下载。Web 直接加载 PyTorch 权重，安卓直接加载 ONNX 模型和 QNN 上下文。

下载 [NanoAvatar-avatar.zip](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-avatar.zip)，解压后将其中的 `avatar/` 目录放到 Web 的 `avatars/person/avatar/` 或安卓源码构建的 `bundle-assets/bundled/avatar/`；APK 已内置同一份人物包，无需单独下载。

<details>
<summary>模型版本说明</summary>

满血版包含 HuBERT FP16 和口型网络 FP32。安卓使用 W8A16 HuBERT、FP32 CPU 人脸与音频编码器、HTP FP16 生成器。HF 中另外的 `quantized/` 是 PyTorch/CUDA 权重，**不能作为安卓模型使用**。其他手机需要匹配的 QNN 模型和运行时支持。

</details>

以下命令默认从仓库根目录执行。

## 运行 Web

安装 CUDA 版 PyTorch 和 Web 依赖：

```shell
python -m pip install --force-reinstall torch==2.10.0 --index-url https://download.pytorch.org/whl/cu128
python -m pip install -r web/requirements.txt
python -m pip install huggingface_hub
hf download wpydcr/NanoAvatar --include "full-precision/*" --local-dir models
python web/run.py --models models/full-precision --avatar avatars/person/avatar
```

打开 **http://127.0.0.1:8765**。启动时会加载模型并准备人物。需要可用的 NVIDIA CUDA 设备，CUDA 不可用时会明确报错。

重装参数用于替换环境中可能已有的同版本 CPU 版 PyTorch。

- **本地 WAV**：16 kHz、单声道、PCM16，最长 90 秒，无需云端账号。
- **文字对话**：在设置中填写 DashScope API Key，或给服务进程设置 `DASHSCOPE_API_KEY`。示例使用通义千问和 CosyVoice，可修改 `web/cloud.py` 更换服务。
- **停止、清空、重连**：停止当前回答、清空对话或建立新连接；清空时保留输入草稿。

## 运行安卓 App

从 [Releases](https://github.com/wpydcr/NanoAvatar/releases) 下载 **NanoAvatar-1.0.0.apk**，直接在手机上安装。安装包内置模型和默认人物，首次启动会自动准备资源，无需电脑或手动复制文件。准备完成后，在设置中填写自己的 DashScope Key 即可使用对话功能。

安卓版目前使用固定 **25 帧/s** 来确保与音频流对齐。适配更老款芯片时，可改为 **12.5 帧/s**，每两帧使用同一张生成图，依然有不错的流畅体验。

<details>
<summary><strong>从源码构建安卓 APK</strong></summary>

安装 JDK 17 或更新版本，以及 Android SDK Platform 35、Build Tools 35.0.0，设置 `JAVA_HOME` 和 `ANDROID_HOME`。从 HF 下载 `android-qnn/`，将其完整内容放入 `payload/`，人物包按上方说明放入 `avatar/`：

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

## 性能

[模型页](https://huggingface.co/wpydcr/NanoAvatar)提供的模型基准数据：

| 模型 | 设备 | 吞吐量 | 首帧延迟 | 内存 / 显存 |
| --- | --- | ---: | ---: | ---: |
| 安卓编译版 | 努比亚 Z60 Ultra · 骁龙 8 Gen 3<br>Android 14 | **32 FPS** | **127 ms** | 785 MiB |
| 满血版 | RTX 4090<br>Windows CUDA | **224 FPS** | **37 ms** | 1119 MiB |

模型生成人脸区域的分辨率为 **256 × 256**，最终画面按原视频尺寸合成。

## 许可

- **MIT**：NanoAvatar 自有源码；Chinese HuBERT 权重及其转换版本保留上游 MIT 许可。
- **CC BY-NC 4.0**：NanoAvatar 口型权重及其量化、编译版本、默认人物包和演示视频。
- **第三方组件**：保留各自原有许可。

各部分的适用范围及完整协议见 [LICENSE](LICENSE)。这些协议分别适用于不同内容，并非同一文件可任选 MIT 或 CC BY-NC 4.0。

商业授权请联系 [wupingyu@mail.ustc.edu.cn](mailto:wupingyu@mail.ustc.edu.cn)。
