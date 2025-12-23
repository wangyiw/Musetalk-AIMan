#!/usr/bin/env python3
import asyncio
import websockets
import json
import cv2
import numpy as np
import sys
import os
import time
import copy
from tqdm import tqdm
import torch
import pickle
import glob

# 添加MuseTalk路径
sys.path.append('/home/paeleap/MuseTalk')

from musetalk.utils.utils import datagen, load_all_model
from musetalk.utils.audio_processor import AudioProcessor
from musetalk.utils.blending import get_image_blending
from musetalk.utils.preprocessing import read_imgs
from transformers import WhisperModel
from musetalk.utils.face_parsing import FaceParsing

class MuseTalkWebSocketService:
    def __init__(self):
        self.device = None
        self.vae = None
        self.unet = None
        self.pe = None
        self.timesteps = None
        self.audio_processor = None
        self.whisper = None
        self.weight_dtype = None
        self.fp = None
        
        # Avatar相关数据（从已保存的文件加载）
        self.avatar_path = "./results/v15/avatars/avator_1"
        self.input_latent_list_cycle = None
        self.coord_list_cycle = None
        self.frame_list_cycle = None
        self.mask_coords_list_cycle = None
        self.mask_list_cycle = None
        
        self.batch_size = 10
        self.fps = 25
        
    async def initialize_models(self):
        """初始化所有模型，复用realtime_inference.py的逻辑"""
        print("正在初始化模型...")
        
        # 设置设备
        self.device = torch.device("cuda:0" if torch.cuda.is_available() else "cpu")
        
        # 加载模型权重 (完全复用原有逻辑)
        self.vae, self.unet, self.pe = load_all_model(
            unet_model_path="./models/musetalkV15/unet.pth",
            vae_type="sd-vae", 
            unet_config="./models/musetalkV15/musetalk.json",
            device=self.device
        )
        self.timesteps = torch.tensor([0], device=self.device)
        
        # 设置模型精度 (完全复用原有逻辑)
        self.pe = self.pe.half().to(self.device)
        self.vae.vae = self.vae.vae.half().to(self.device)
        self.unet.model = self.unet.model.half().to(self.device)
        
        # 初始化音频处理器 (完全复用原有逻辑)
        self.audio_processor = AudioProcessor(feature_extractor_path="./models/whisper")
        self.weight_dtype = self.unet.model.dtype
        self.whisper = WhisperModel.from_pretrained("./models/whisper")
        self.whisper = self.whisper.to(device=self.device, dtype=self.weight_dtype).eval()
        self.whisper.requires_grad_(False)
        
        # 初始化面部解析器 (完全复用原有逻辑)
        self.fp = FaceParsing(left_cheek_width=90, right_cheek_width=90)
        
        # 加载已保存的Avatar数据
        await self.load_avatar_data()
        
        print("模型初始化完成！")
        
    async def load_avatar_data(self):
        """加载已保存的Avatar预处理数据"""
        print("正在加载Avatar数据...")
        
        # 加载latents
        latents_path = f"{self.avatar_path}/latents.pt"
        self.input_latent_list_cycle = torch.load(latents_path)
        
        # 加载坐标
        coords_path = f"{self.avatar_path}/coords.pkl"
        with open(coords_path, 'rb') as f:
            self.coord_list_cycle = pickle.load(f)
            
        # 加载图片帧
        input_img_list = glob.glob(os.path.join(f"{self.avatar_path}/full_imgs", '*.[jpJP][pnPN]*[gG]'))
        input_img_list = sorted(input_img_list, key=lambda x: int(os.path.splitext(os.path.basename(x))[0]))
        self.frame_list_cycle = read_imgs(input_img_list)
        
        # 加载mask坐标
        mask_coords_path = f"{self.avatar_path}/mask_coords.pkl"
        with open(mask_coords_path, 'rb') as f:
            self.mask_coords_list_cycle = pickle.load(f)
            
        # 加载mask图片
        input_mask_list = glob.glob(os.path.join(f"{self.avatar_path}/mask", '*.[jpJP][pnPN]*[gG]'))
        input_mask_list = sorted(input_mask_list, key=lambda x: int(os.path.splitext(os.path.basename(x))[0]))
        self.mask_list_cycle = read_imgs(input_mask_list)
        
        print("Avatar数据加载完成！")
        
    async def process_audio_to_frames_optimized(self, audio_path, websocket, options=None):
        """优化版：处理音频并实时生成图片帧"""
        if options is None:
            options = {
                "jpeg_quality": 70,  # 降低JPEG质量以减小文件大小
                "batch_send": False,  # 是否批量发送
                "verbose": False      # 减少打印输出
            }
            
        print(f"开始处理音频: {audio_path}")
        
        # 音频特征提取 (完全复用原有逻辑)
        start_time = time.time()
        whisper_input_features, librosa_length = self.audio_processor.get_audio_feature(
            audio_path, weight_dtype=self.weight_dtype
        )
        whisper_chunks = self.audio_processor.get_whisper_chunk(
            whisper_input_features,
            self.device,
            self.weight_dtype,
            self.whisper,
            librosa_length,
            fps=self.fps,
            audio_padding_length_left=2,
            audio_padding_length_right=2,
        )
        audio_time = time.time() - start_time
        print(f"音频处理耗时: {audio_time * 1000:.2f}ms")
        
        # 推理 (优化版)
        video_num = len(whisper_chunks)
        idx = 0
        last_progress_time = time.time()
        
        print(f"总计需要处理 {video_num} 帧")
        
        # 批量推理 (完全复用原有逻辑)
        gen = datagen(whisper_chunks, self.input_latent_list_cycle, self.batch_size)
        inference_start = time.time()
        
        for i, (whisper_batch, latent_batch) in enumerate(tqdm(gen, total=int(np.ceil(float(video_num) / self.batch_size)), disable=not options.get("verbose", False))):
            batch_start = time.time()
            
            # GPU推理
            audio_feature_batch = self.pe(whisper_batch.to(self.device))
            latent_batch = latent_batch.to(device=self.device, dtype=self.unet.model.dtype)
            
            pred_latents = self.unet.model(latent_batch,
                                         self.timesteps,
                                         encoder_hidden_states=audio_feature_batch).sample
            pred_latents = pred_latents.to(device=self.device, dtype=self.vae.vae.dtype)
            recon = self.vae.decode_latents(pred_latents)
            
            # 批量处理帧并发送
            batch_frames = []
            for res_frame in recon:
                if idx >= video_num:
                    break
                    
                # 完全复用原有的图片混合逻辑
                bbox = self.coord_list_cycle[idx % len(self.coord_list_cycle)]
                ori_frame = copy.deepcopy(self.frame_list_cycle[idx % len(self.frame_list_cycle)])
                x1, y1, x2, y2 = bbox
                try:
                    res_frame = cv2.resize(res_frame.astype(np.uint8), (x2 - x1, y2 - y1))
                except:
                    idx += 1
                    continue
                    
                mask = self.mask_list_cycle[idx % len(self.mask_list_cycle)]
                mask_crop_box = self.mask_coords_list_cycle[idx % len(self.mask_coords_list_cycle)]
                combine_frame = get_image_blending(ori_frame, res_frame, bbox, mask, mask_crop_box)
                
                # 高效编码
                jpeg_quality = options.get("jpeg_quality", 70)
                encode_params = [cv2.IMWRITE_JPEG_QUALITY, jpeg_quality]
                if jpeg_quality < 50:
                    encode_params.extend([cv2.IMWRITE_JPEG_OPTIMIZE, 1])
                    
                _, buffer = cv2.imencode('.jpg', combine_frame, encode_params)
                frame_bytes = buffer.tobytes()
                
                if options.get("batch_send", False):
                    batch_frames.append(frame_bytes)
                else:
                    # 立即发送
                    try:
                        await websocket.send(frame_bytes)
                        if options.get("verbose", False):
                            print(f"发送第 {idx + 1} 帧")
                    except websockets.exceptions.ConnectionClosed:
                        print("WebSocket连接已关闭")
                        return
                    except Exception as e:
                        print(f"发送帧 {idx + 1} 失败: {e}")
                        return
                        
                idx += 1
                
                # 更频繁的心跳和进度（每50帧或每2秒）
                current_time = time.time()
                if idx % 50 == 0 or (current_time - last_progress_time) > 2:
                    try:
                        progress = {
                            "status": "progress",
                            "current_frame": idx,
                            "total_frames": video_num,
                            "progress_percent": (idx / video_num) * 100,
                            "elapsed_time": current_time - inference_start
                        }
                        await websocket.send(json.dumps(progress))
                        last_progress_time = current_time
                        
                        # 只在重要进度点打印，减少输出
                        if idx % 100 == 0:
                            print(f"进度: {idx}/{video_num} ({progress['progress_percent']:.1f}%)")
                        
                        # 定期清理GPU缓存
                        if idx % 500 == 0:
                            torch.cuda.empty_cache()
                            print("清理GPU缓存")
                            
                        # 给WebSocket一个短暂的处理时间
                        await asyncio.sleep(0.001)  # 1ms的异步让步
                            
                    except Exception as e:
                        print(f"发送进度失败: {e}")
                        return  # 如果无法发送进度，停止处理
            
            # 批量发送模式
            if options.get("batch_send", False) and batch_frames:
                try:
                    for frame_bytes in batch_frames:
                        await websocket.send(frame_bytes)
                    if options.get("verbose", False):
                        print(f"批量发送 {len(batch_frames)} 帧")
                except websockets.exceptions.ConnectionClosed:
                    print("WebSocket连接已关闭")
                    return
                except Exception as e:
                    print(f"批量发送失败: {e}")
                    return
        
        total_time = time.time() - inference_start
        print(f'处理完成 {idx} 帧，推理耗时: {total_time:.2f}s，速度: {idx/total_time:.1f}帧/秒')
        
        # 发送完成信号
        try:
            await websocket.send(json.dumps({
                "status": "completed", 
                "total_frames": idx,
                "processing_time": total_time,
                "fps": idx/total_time
            }))
        except Exception as e:
            print(f"发送完成信号失败: {e}")

    async def handle_client(self, websocket):
        """处理WebSocket客户端连接"""
        print(f"新客户端连接: {websocket.remote_address}")
        
        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                    audio_path = data.get('audio_path')
                    options = data.get('options', {})
                    
                    if not audio_path:
                        await websocket.send(json.dumps({"error": "缺少audio_path参数"}))
                        continue
                        
                    if not os.path.exists(audio_path):
                        await websocket.send(json.dumps({"error": f"音频文件不存在: {audio_path}"}))
                        continue
                        
                    # 发送开始处理信号
                    await websocket.send(json.dumps({"status": "processing", "audio_path": audio_path}))
                    
                    # 处理音频并发送帧
                    await self.process_audio_to_frames_optimized(audio_path, websocket, options)
                    
                except json.JSONDecodeError:
                    await websocket.send(json.dumps({"error": "无效的JSON格式"}))
                except Exception as e:
                    await websocket.send(json.dumps({"error": f"处理错误: {str(e)}"}))
                    
        except websockets.exceptions.ConnectionClosed:
            print(f"客户端断开连接: {websocket.remote_address}")
        except Exception as e:
            print(f"连接错误: {e}")

    async def start_server(self, host="0.0.0.0", port=8765):
        """启动WebSocket服务器"""
        print(f"启动WebSocket服务器在 ws://{host}:{port}")
        # 设置超长超时时间，适合长时间处理
        await websockets.serve(
            self.handle_client, 
            host, 
            port,
            ping_interval=60,      # 每60秒发送ping（更长间隔）
            ping_timeout=30,       # ping超时30秒（更宽松）
            close_timeout=30,      # 关闭超时30秒
            max_size=10**7,        # 最大消息大小10MB
            compression=None       # 禁用压缩以提高速度
        )

async def main():
    service = MuseTalkWebSocketService()
    await service.initialize_models()
    await service.start_server()
    
    # 保持服务器运行
    print("WebSocket服务器正在运行，按Ctrl+C停止...")
    try:
        await asyncio.Future()  # 永远等待
    except KeyboardInterrupt:
        print("服务器关闭")

if __name__ == "__main__":
    asyncio.run(main()) 