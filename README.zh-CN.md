<div align="center">
<h1>📱 NanoAvatar</h1>
<h3>改变了数字人应用的使用频率和规模上限</h3>
<p>📱 <strong>23年手机芯片 · 41 FPS</strong> · ⚡ <strong>首帧延迟 103 ms</strong></p>
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

**直接在手机上生成高保真数字人视频，无需云端 GPU。**

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

**断网体验端侧数字人生成模型。**

<a id="quick-start"></a>
<a id="run-on-android"></a>
## 🚀 手机上体验

**版本 1.0.0** · [完整版 APK](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar.apk) · [Lite APK](https://github.com/wpydcr/NanoAvatar/releases/latest/download/NanoAvatar-Lite.apk)

1. 安装 APK 并打开 App。
2. 默认进入**体验模式**。长按录音按钮说话，松开后用自己的声音驱动人物。
3. 如需 AI 对话，切换到**交互模式**并填写阿里云 API key。

- **完整版**：生成帧率固定为 **25 FPS**。
- **Lite**：轻量化模型，生成帧率固定为 **12.5 FPS**，安装包更小，算力需求更低。

两版都会实时显示 FPS 和首帧时间。

<a id="performance"></a>
## 📊 性能实测

| 模型 | 设备 | 模型 FPS | 首帧 | 内存 / 显存估算 |
| --- | --- | ---: | ---: | ---: |
| 📱 NanoAvatar 完整版 | 23年 · 骁龙 8 Gen 3 | **39 FPS** | **115 ms** | **834 MiB** |
| 📱 NanoAvatar Lite | 23年 · 骁龙 8 Gen 3 | **41 FPS** | **103 ms** | **700 MiB** |
| 📱 NanoAvatar Lite | 21年 · 骁龙 8 Gen 1 | **18 FPS** | **183 ms** | **693 MiB** |
| ⚡ 旧量化版（CUDA DLL） | RTX 4090 · Windows CUDA | **333 FPS** | **18 ms** | **834 MiB** |
| 🖥️ 满血版 | RTX 4090 · Windows CUDA | **224 FPS** | **37 ms** | **1119 MiB** |

🌊 **流式生成，边收音频边开口。** 接入流式大模型和流式 TTS，实测约 **0.3 秒开始说话**，无需等待整段音频生成完成。

<a id="run-on-web"></a>
## 🖥️ Web：用本地 NVIDIA GPU 运行

Web 是简单体验代码，上传视频即可体验。实际使用时，制作人物包的效果最优。

**环境要求：** Python 3.11、NVIDIA GPU、CUDA 版 PyTorch。

获取源码：

```shell
git clone https://github.com/wpydcr/NanoAvatar.git
cd NanoAvatar
```

安装依赖：

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
python web/run.py --models models/full-precision
```

**⚡ 量化版（CUDA 版 PyTorch）**

HuBERT W8A16，混合 INT8 口型模型。

```shell
hf download wpydcr/NanoAvatar --include "quantized/*" --local-dir models
python web/run.py --models models/quantized
```

Web 会根据 `--models` 指向的目录选择模型，直接加载量化 TorchScript 文件，无需自定义 CUDA DLL。上表旧量化版的性能数据不代表当前可移植模型的性能。

打开 **http://127.0.0.1:8890**。上传一小段单人、正面、连续镜头的视频，等待人物准备完成，再上传音频驱动人物。视频最大 256 MB，默认取前 10 秒。

重装参数用于替换环境中可能已有的同版本 CPU 版 PyTorch。

- **本地音频**：支持 WAV、MP3 等浏览器可解码的格式，最大 32 MB、最长 90 秒，无需云端账号。
- **文字对话**：在“文字对话设置”中填写 DashScope API Key 并点击“应用设置”，或给服务进程设置 `DASHSCOPE_API_KEY`。示例使用通义千问和 CosyVoice，可修改 `web/cloud.py` 更换服务。
- **停止、重连**：停止当前回答，或在断开后重新连接。

## 🛠️ 模型与源码

本仓库提供 Web 推理源码。模型权重放在 Hugging Face，APK 放在 Releases。

| 平台 | 源码 | Hugging Face 权重 |
| --- | --- | --- |
| **Web 满血版** | [`web/`](web/) | `full-precision/`（come soon） |
| **Web 量化版** | [`web/`](web/) | [`quantized/`](https://huggingface.co/wpydcr/NanoAvatar/tree/main/quantized) |

[`android/`](android/) 是 APK 的源码。

## ⭐ 支持 NanoAvatar

如果 NanoAvatar 对你有帮助，欢迎 **点一个 Star**。也欢迎在 [Issues](https://github.com/wpydcr/NanoAvatar/issues) 分享你的作品或反馈问题。反馈性能时，请附上设备、系统版本和模型包。

<a id="license"></a>
## 📄 许可

- **MIT**：NanoAvatar 自有源码；Chinese HuBERT 权重及其转换版本保留上游 MIT 许可。
- **CC BY-NC 4.0**：NanoAvatar 口型权重及其量化、编译版本和演示视频。
- **第三方组件**：保留各自原有许可。

各部分的适用范围及完整协议见 [LICENSE](LICENSE)。这些协议分别适用于不同内容，并非同一文件可任选 MIT 或 CC BY-NC 4.0。

商业授权请联系 [wupingyu@mail.ustc.edu.cn](mailto:wupingyu@mail.ustc.edu.cn)。
