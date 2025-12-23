# MuseTalk

<strong>MuseTalk: Real-Time High-Fidelity Video Dubbing via Spatio-Temporal Sampling</strong>



**[github](https://github.com/TMElyralab/MuseTalk)** | **[huggingface](https://huggingface.co/TMElyralab/MuseTalk)** | **[Technical report](https://arxiv.org/abs/2410.10122)**

`MuseTalk` 是一个**实时高质量**唇语同步模型，在 NVIDIA Tesla V100 上可达到 30fps+。

## 🌟 特性
- **实时推理**: 在 NVIDIA Tesla V100 上支持 30fps+ 实时生成
- **多语言支持**: 支持中文、英文、日文等多种语言
- **高质量输出**: 256x256 高分辨率面部生成
- **WebSocket API**: 支持实时数字人应用

## 📦 环境安装

### 1. 创建 Conda 环境
推荐使用 Python 3.10 和 CUDA 11.7:

```bash
conda create -n MuseTalk python==3.10
conda activate MuseTalk
```

### 2. 安装 PyTorch
```bash
# 使用 pip 安装
pip install torch==2.0.1 torchvision==0.15.2 torchaudio==2.0.2 --index-url https://download.pytorch.org/whl/cu118

# 或使用 conda 安装
conda install pytorch==2.0.1 torchvision==0.15.2 torchaudio==2.0.2 pytorch-cuda=11.8 -c pytorch -c nvidia
```

### 3. 安装项目依赖
```bash
# 安装基础依赖
pip install -r requirements.txt

# 安装 MMLab 生态包
pip install --no-cache-dir -U openmim
mim install mmengine
mim install "mmcv==2.0.1"
mim install "mmdet==3.1.0"
mim install "mmpose==1.1.0"

# WebSocket 服务额外依赖
pip install websockets opencv-python
```

### 4. 安装 FFmpeg
**Linux:**
```bash
sudo apt-get install ffmpeg
# 或下载静态版本: https://github.com/BtbN/FFmpeg-Builds/releases
```

**Windows:**
1. 从 [FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds/releases) 下载
2. 解压并添加 `bin` 目录到系统 PATH
3. 验证: `ffmpeg -version`

### 5. 下载模型权重
```bash
# Linux
bash ./download_weights.sh

# Windows
download_weights.bat
```

模型文件结构:
```
./models/
├── musetalkV15/
│   ├── musetalk.json
│   └── unet.pth
├── dwpose/
│   └── dw-ll_ucoco_384.pth
├── face-parse-bisent/
│   ├── 79999_iter.pth
│   └── resnet18-5c106cde.pth
├── sd-vae/
│   ├── config.json
│   └── diffusion_pytorch_model.bin
└── whisper/
    ├── config.json
    ├── pytorch_model.bin
    └── preprocessor_config.json
```

## 🚀 使用方法

### 实时推理
```bash
# MuseTalk 1.5 实时推理 (推荐)
python3 -m scripts.realtime_inference \
    --inference_config ./configs/inference/realtime.yaml \
    --result_dir ./results/realtime \
    --unet_model_path ./models/musetalkV15/unet.pth \
    --unet_config ./models/musetalkV15/musetalk.json \
    --version v15 \
    --fps 25 \
    --batch_size 5
```

### 标准推理
```bash
# MuseTalk 1.5 标准推理
python3 -m scripts.inference \
    --inference_config ./configs/inference/test.yaml \
    --result_dir ./results/test \
    --unet_model_path ./models/musetalkV15/unet.pth \
    --unet_config ./models/musetalkV15/musetalk.json \
    --version v15
```

### Gradio 演示界面
```bash
# 启动 Web 界面
python app.py --use_float16
```

### 配置文件说明
在 `configs/inference/test.yaml` 或 `realtime.yaml` 中配置:
- `video_path`: 输入视频路径
- `audio_path`: 输入音频路径
- `preparation`: 处理新头像时设为 `True`

## 🌐 WebSocket API 服务

### 启动 WebSocket 服务
```bash
# 进入服务目录
cd service

# 启动 WebSocket 服务器
python websocket_service.py
```

服务将在 `ws://0.0.0.0:8765` 启动，支持外部网络访问。

### API 使用示例
```python
import asyncio
import websockets
import json

async def test_client():
    uri = "ws://192.168.10.172:8765"
    async with websockets.connect(uri) as websocket:
        # 发送处理请求
        request = {
            "audio_path": "/path/to/audio.wav",
            "options": {
                "jpeg_quality": 70,
                "batch_send": False,
                "verbose": True
            }
        }
        await websocket.send(json.dumps(request))
        
        # 接收图片帧和状态消息
        async for message in websocket:
            if isinstance(message, str):
                # JSON 状态消息
                data = json.loads(message)
                print(f"状态: {data}")
            else:
                # 二进制 JPEG 图片帧
                print(f"收到图片帧: {len(message)} bytes")

asyncio.run(test_client())
```

### 测试客户端
```bash
# 运行测试客户端
python test_client.py
```

## ⚙️ 系统服务配置 (开机自启)

### 创建系统服务
项目已包含服务配置文件 `service/musetalk-websocket.service`。

### 安装服务
```bash
# 复制服务文件
sudo cp service/musetalk-websocket.service /etc/systemd/system/

# 重新加载 systemd 配置
sudo systemctl daemon-reload

# 启用开机自启
sudo systemctl enable musetalk-websocket.service

# 启动服务
sudo systemctl start musetalk-websocket.service
```

### 服务管理命令
```bash
# 查看服务状态
sudo systemctl status musetalk-websocket.service

# 停止服务
sudo systemctl stop musetalk-websocket.service

# 重启服务
sudo systemctl restart musetalk-websocket.service

# 查看服务日志
sudo journalctl -u musetalk-websocket.service -f
```

### 验证服务运行
```bash
# 检查端口监听
sudo netstat -tlnp | grep :8765

# 测试连接
python service/test_client.py
```

## 📋 API 文档

详细的 WebSocket API 使用说明请参考：[service/API_README.md](service/API_README.md)

- **服务器地址**: `ws://192.168.10.172:8765`
- **处理速度**: 24-28帧/秒
- **支持格式**: JPEG 二进制帧流
- **实时监控**: 进度反馈和状态消息

## 🛠️ 训练 (可选)

### 数据预处理
```bash
python -m scripts.preprocess --config ./configs/training/preprocess.yaml
```

### 训练过程
```bash
# 第一阶段
bash train.sh stage1

# 第二阶段  
bash train.sh stage2
```

### GPU 内存需求
- **Stage 1**: 批量大小 32，约 74GB 显存
- **Stage 2**: 批量大小 2，约 85GB 显存

## 📝 注意事项

1. **推荐视频帧率**: 25fps (与训练时一致)
2. **最佳硬件**: NVIDIA Tesla V100 或更高
3. **最低要求**: RTX 3050 Ti (4GB VRAM, fp16 模式)
4. **网络访问**: WebSocket 服务绑定 0.0.0.0，支持外部访问
5. **资源管理**: 服务自动管理 GPU 缓存，定期清理

## 🤝 贡献

欢迎提交 Issues 和 Pull Requests 来改进此项目！

## 📄 License

此项目遵循原始 MuseTalk 项目的开源协议。

## 🔗 相关链接

- [技术报告](https://arxiv.org/abs/2410.10122)
- [Hugging Face 模型](https://huggingface.co/TMElyralab/MuseTalk)
- [在线演示](https://huggingface.co/spaces/TMElyralab/MuseTalk)
