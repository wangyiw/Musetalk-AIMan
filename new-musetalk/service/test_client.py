#!/usr/bin/env python3
import asyncio
import websockets
import json
import os
import time

async def test_client(uri="ws://192.168.10.172:8765"):
    """测试WebSocket客户端"""
    print(f"连接到WebSocket服务器: {uri}")
    
    # 测试音频文件路径
    test_request = {
        "audio_path": "/home/paeleap/MuseTalk/data/audio/yongen.wav",
        "options": {
            "jpeg_quality": 70,
            "batch_send": False,
            "verbose": True
        }
    }
    
    try:
        async with websockets.connect(uri) as websocket:
            print("连接成功！")
            
            # 发送测试请求
            message = json.dumps(test_request)
            await websocket.send(message)
            print(f"发送请求: {test_request['audio_path']}")
            
            frame_count = 0
            start_time = None
            last_print_time = time.time()
            
            # 监听响应
            async for message in websocket:
                # 先检查消息类型
                if isinstance(message, str):
                    # 字符串消息 - JSON状态消息
                    try:
                        data = json.loads(message)
                        print(f"\n状态: {data}")
                        
                        if data.get('status') == 'processing':
                            start_time = time.time()
                            print("开始处理...")
                            
                        elif data.get('status') == 'progress':
                            current = data.get('current_frame', 0)
                            total = data.get('total_frames', 0)
                            percent = data.get('progress_percent', 0)
                            elapsed = data.get('elapsed_time', 0)
                            print(f"进度: {current}/{total} ({percent:.1f}%) - 耗时: {elapsed:.1f}s")
                            
                        elif data.get('status') == 'completed':
                            total_frames = data.get('total_frames', 0)
                            processing_time = data.get('processing_time', 0)
                            fps = data.get('fps', 0)
                            print(f"\n✅ 处理完成!")
                            print(f"总帧数: {total_frames}")
                            print(f"处理时间: {processing_time:.2f}s")
                            print(f"处理速度: {fps:.1f}帧/秒")
                            break
                            
                        elif 'error' in data:
                            print(f"❌ 错误: {data['error']}")
                            break
                            
                    except json.JSONDecodeError:
                        print(f"无法解析JSON消息: {message}")
                else:
                    # 二进制数据（图片帧）
                    frame_count += 1
                    
                    current_time = time.time()
                    if current_time - last_print_time >= 1.0:  # 每秒打印一次
                        if start_time:
                            elapsed = current_time - start_time
                            fps = frame_count / elapsed if elapsed > 0 else 0
                            
                            # 估算文件大小
                            frame_size = len(message)
                            total_size_mb = (frame_size * frame_count) / (1024 * 1024)
                            
                            print(f"📸 接收帧: {frame_count}, 速度: {fps:.1f}帧/秒, 大小: {frame_size/1024:.1f}KB/帧, 总计: {total_size_mb:.1f}MB")
                        last_print_time = current_time
                        
    except websockets.exceptions.ConnectionClosed:
        print("\n连接已关闭")
    except Exception as e:
        print(f"连接失败: {e}")

async def main():
    """主函数"""
    print("🚀 MuseTalk WebSocket 测试客户端")
    print("=" * 50)
    print("功能说明:")
    print("- 自动发送测试请求")
    print("- 实时显示处理进度和帧接收情况")
    print("=" * 50)
    
    await test_client()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n\n程序被中断") 