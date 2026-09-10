"""Audio encoder, reference-face encoder and RGB generator."""
import torch
import torch.nn as nn
import torch.nn.functional as F


class UnifiedAudioModel(nn.Module):
    def __init__(self, s1_cfg, s2_params):
        super().__init__()
        k3, k4, k5 = s2_params

        # 1. 前置特征提取层
        self.down_blocks = nn.ModuleList()
        for name in ["down.0", "down.1", "down.2"]:
            cfg = s1_cfg[name]
            self.down_blocks.append(
                nn.Sequential(
                    nn.Conv1d(
                        in_channels=cfg["in_channels"],
                        out_channels=cfg["out_channels"],
                        kernel_size=cfg["kernel_size"],
                        stride=cfg["stride"],
                        padding=cfg["padding"],
                        dilation=cfg["dilation"],
                    ),
                    nn.SiLU(),
                )
            )

        # 2. 残差卷积层
        self.d3_conv = nn.Conv1d(
            10, 10, kernel_size=k3, stride=1, padding=(k3 - 1) // 2
        )
        self.d3_norm = nn.LayerNorm(512, eps=1e-5)
        self.d4_conv = nn.Conv1d(
            10, 10, kernel_size=k4, stride=2, padding=(k4 - 1) // 2
        )
        self.d5_conv = nn.Conv1d(
            10, 10, kernel_size=k5, stride=1, padding=(k5 - 1) // 2
        )
        self.d5_norm = nn.LayerNorm(256, eps=1e-5)

        # 3. 序列建模与头部映射
        self.lstm = nn.LSTM(
            input_size=256, hidden_size=576, num_layers=2, batch_first=False
        )
        self.fc1 = nn.Linear(360, 512)
        self.ln = nn.LayerNorm(512, eps=1e-5)
        self.fc2 = nn.Linear(512, 512)

    def _features(self, audio):
        x = audio
        for block in self.down_blocks:
            x = block(x)

        h = self.d3_conv(x)
        h = self.d3_norm(h)
        h += x  # In-place 加法
        x = F.silu(h, inplace=True)  # In-place 啟動函數

        x = F.silu(self.d4_conv(x), inplace=True)

        h = self.d5_conv(x)
        h = self.d5_norm(h)
        h += x  # In-place 加法
        x = F.silu(h, inplace=True)

        return x

    def _head(self, x):
        B, Seq, Dim = x.shape
        x = x.reshape(B, Seq, 16, 36).permute(0, 2, 1, 3).reshape(B * 16, Seq * 36)

        x = self.fc1(x)
        x = self.ln(x)
        x = F.silu(x, inplace=True)
        x = self.fc2(x)

        return x.reshape(B, 4, 4, 512)

    def forward(self, audio, hn, cn):
        x = self._features(audio).transpose(0, 1)
        sequence, (hn_new, cn_new) = self.lstm(x, (hn, cn))
        return self._head(sequence.permute(1, 0, 2)), hn_new, cn_new

    def forward_sequence(self, audio, hn, cn, start_frame, reset_frames=10):
        """Consecutive frames are LSTM timesteps, never independent batch items.

        Each frame contributes ten feature timesteps. Join only up to the next
        existing reset boundary; this retains the original hidden-state order.
        Independent convolutions and the output head use the frame batch.
        """
        x = self._features(audio)
        outputs = []
        offset = 0
        while offset < len(x):
            position = (start_frame + offset) % reset_frames
            if position == 0:
                hn, cn = torch.zeros_like(hn), torch.zeros_like(cn)
            count = min(len(x) - offset, reset_frames - position)
            sequence = x[offset:offset + count].reshape(-1, 1, x.shape[-1])
            values, (hn, cn) = self.lstm(sequence, (hn, cn))
            outputs.append(values.reshape(count, x.shape[1], -1))
            offset += count
        return self._head(torch.cat(outputs, dim=0)), hn, cn

import torch.nn as nn


class ResBlockDown(nn.Module):
    def __init__(self, in_channels, out_channels):
        super().__init__()
        self.conv_down = nn.Conv2d(
            in_channels, out_channels, kernel_size=3, stride=2, padding=1
        )
        self.silu1 = nn.SiLU(inplace=True)
        self.conv_res = nn.Conv2d(
            out_channels, out_channels, kernel_size=3, stride=1, padding=1
        )
        self.gn = nn.GroupNorm(8, out_channels)
        self.silu2 = nn.SiLU(inplace=True)

    def forward(self, x):
        x_half = self.silu1(self.conv_down(x))
        res = self.gn(self.conv_res(x_half))
        res += x_half
        out = self.silu2(res)
        return out


class FaceEncoder(nn.Module):
    def __init__(self):
        super().__init__()
        self.conv_in = nn.Sequential(
            nn.Conv2d(7, 16, kernel_size=3, stride=1, padding=1),
            nn.SiLU(inplace=True),
            nn.Conv2d(16, 16, kernel_size=3, stride=1, padding=1),
            nn.SiLU(inplace=True),
        )
        self.down_blocks = nn.ModuleList(
            [
                ResBlockDown(16, 32),
                ResBlockDown(32, 64),
                ResBlockDown(64, 128),
                ResBlockDown(128, 256),
                ResBlockDown(256, 512),
                ResBlockDown(512, 512),
            ]
        )

    def forward(self, x):
        outputs = []
        x = self.conv_in(x)
        outputs.append(x)  # Feature 0
        for block in self.down_blocks:
            x = block(x)
            outputs.append(x)  # Feature 1-6
        return outputs

import torch
import torch.nn as nn
import torch.nn.functional as F


class ResBlock(nn.Module):
    def __init__(self, in_channels, out_channels):
        super().__init__()
        self.conv = nn.Conv2d(in_channels, out_channels, kernel_size=3, padding=1)
        self.gn = nn.GroupNorm(8, out_channels, eps=1e-5)
        self.silu = nn.SiLU(inplace=True)

    def forward(self, x):
        residual = x
        h = self.conv(x)
        h = self.gn(h)
        h += residual
        return self.silu(h)


class UpBlock(nn.Module):
    def __init__(self, in_channels, out_channels):
        super().__init__()
        self.conv0 = nn.Conv2d(in_channels, out_channels, kernel_size=3, padding=1)
        self.silu0 = nn.SiLU(inplace=True)
        self.res_block = ResBlock(out_channels, out_channels)

    def forward(self, x):
        x = F.interpolate(x, scale_factor=2, mode="nearest")
        x = self.conv0(x)
        x = self.silu0(x)
        x = self.res_block(x)
        return x


class Generator(nn.Module):
    def __init__(self):
        super().__init__()
        layer_configs = [
            (1024, 512),
            (1024, 512),
            (768, 384),
            (512, 256),
            (320, 128),
            (160, 64),
        ]

        self.audio_norm = nn.LayerNorm(512, eps=1e-5)
        self.face_norm = nn.LayerNorm(512, eps=1e-5)

        self.up_blocks = nn.ModuleList()
        for in_c, out_c in layer_configs:
            self.up_blocks.append(UpBlock(in_c, out_c))

        last_out_c = layer_configs[-1][1]
        self.up_out = ResBlock(last_out_c + 16, last_out_c + 16)
        self.out_conv = nn.Conv2d(last_out_c + 16, 3, kernel_size=3, padding=1)
        self.sigmoid = nn.Sigmoid()

    def forward(self, audio, down5, down4, down3, down2, down1, down0, conv_in):
        a = self.audio_norm(audio).permute(0, 3, 1, 2)
        a.mul_(1.5)
        f = down5.permute(0, 2, 3, 1)
        f = self.face_norm(f).permute(0, 3, 1, 2)
        x = torch.cat([a, f], dim=1)

        skips = [down4, down3, down2, down1, down0, conv_in]
        for i, block in enumerate(self.up_blocks):
            x = block(x)
            if i < len(skips):
                x = torch.cat([x, skips[i]], dim=1)

        x = self.up_out(x)
        x = self.out_conv(x)
        x = self.sigmoid(x)
        return x

import torch
import torch.nn as nn



class LiveTalkingModel(nn.Module):
    """
    端到端的 LiveTalking 完整模型，封装了音频提取、面部编码和图像生成三个阶段。
    """

    def __init__(self, s1_cfg, s2_params):
        super().__init__()
        # 1. 初始化三大子模块
        self.audio_encoder = UnifiedAudioModel(s1_cfg, s2_params)
        self.face_encoder = FaceEncoder()
        self.generator = Generator()

    def forward(self, mel, img, hn, cn):
        """
        前向传播
        :param mel: 驱动音频特征 (Tensor)
        :param img: 参考面部图像 (Tensor)
        :param hn: LSTM 隐藏状态 (Tensor)
        :param cn: LSTM 细胞状态 (Tensor)
        :return: 生成的图像 out_img, 更新后的 hn_new, 更新后的 cn_new
        """
        # Step 1: 提取音频特征，并更新 LSTM 状态
        audio_out = self.audio_encoder(mel, hn, cn)
        # 兼容旧代码：audio_encoder可能返回 (feat, hn, cn) 或 feat

        # 为了更稳健，我们通过解包处理
        if isinstance(audio_out, tuple):
            feat, hn_new, cn_new = audio_out
        else:
            # 如果只返回特征（不应该发生，除非被修改）
            feat = audio_out
            hn_new, cn_new = hn, cn

        # Step 2: 提取面部图像的多尺度跳跃连接特征
        # outputs 包含 7 个特征层 [conv_in, down0, down1, down2, down3, down4, down5]
        face_skips = self.face_encoder(img)

        # Step 3: 将音频特征和倒序的面部特征送入生成器
        out_img = self.generator(
            feat,  # 使用解包后的 feat
            face_skips[6],  # down5
            face_skips[5],  # down4
            face_skips[4],  # down3
            face_skips[3],  # down2
            face_skips[2],  # down1
            face_skips[1],  # down0
            face_skips[0],  # conv_in
        )

        return out_img, hn_new, cn_new

    def load_pretrained_parts(self, path_part1, path_part2, path_part3, device):
        """
        便捷方法：分别加载三个阶段的预训练权重
        """
        self.audio_encoder.load_state_dict(torch.load(path_part1, map_location=device))
        self.face_encoder.load_state_dict(torch.load(path_part2, map_location=device))
        self.generator.load_state_dict(torch.load(path_part3, map_location=device))
        self.eval()
        self.to(device)