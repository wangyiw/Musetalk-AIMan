package com.gyq.service.BlockingQueueService;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyq.FileDto;

import jakarta.websocket.Session;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 优化版本的MuseTalkWebSocketClient
 * 使用视频帧缓冲器提升性能和可靠性
 */
public class OptimizedMuseTalkWebSocketClient extends WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(OptimizedMuseTalkWebSocketClient.class);
    
    private final Session userSession;
    private final AtomicBoolean isCompleted = new AtomicBoolean(false);
    private final AtomicInteger messageCount = new AtomicInteger(0);
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong totalFrameSize = new AtomicLong(0);
    
    public final ObjectMapper objectMapper = new ObjectMapper();
    private volatile CountDownLatch doneLatch = new CountDownLatch(1);
    
    // 视频帧缓冲器
    private final VideoFrameBuffer frameBuffer;
    private final String sessionId;
    
    public OptimizedMuseTalkWebSocketClient(URI serverUri, Session userSession, String sessionId) {
        super(serverUri);
        this.userSession = userSession;
        this.sessionId = sessionId;
        this.frameBuffer = new VideoFrameBuffer(userSession, sessionId);
    }
    
    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        logger.info("成功连接到MuseTalk服务端，握手状态: {} {}", 
            serverHandshake.getHttpStatus(), serverHandshake.getHttpStatusMessage());
        
        // 启动视频帧缓冲器
        frameBuffer.start();
        logger.info("视频帧缓冲器已启动: {}", sessionId);
    }
    
    public void sendAudioRequest(String audioPath, String emotion) throws IOException {
        try {
            if (!isOpen()) {
                throw new IOException("WebSocket连接未建立或已关闭，当前状态: " + getReadyState());
            }
            
            Map<String, Object> request = Map.of(
                "audio_path", audioPath,
                "avatar", emotion,
                "options", Map.of(
                    "jpeg_quality", 50,
                    "batch_send", false,
                    "verbose", false
                )
            );
            
            String json = objectMapper.writeValueAsString(request);
            logger.info("调用musetalk入参：{}", json);
            
            send(json);
            isCompleted.set(false);
            
            // 重置统计信息
            frameCount.set(0);
            totalFrameSize.set(0);
            
            logger.info("音频请求已发送，等待处理完成...");
        } catch (Exception e) {
            logger.error("发送音频请求失败: {}", e.getMessage(), e);
            this.close();
            throw e;
        }
    }
    
    @Override
    public void onMessage(String message) {
        int count = messageCount.incrementAndGet();
        logger.debug("接收到第{}条JSON消息: {}", count, message);
        
        try {
            JSONObject jsonObject = JSONObject.parseObject(message);
            String status = jsonObject.getString("status");
            
            // 处理结束标识
            if ("completed".equals(status)) {
                logger.info("推理完成此段，设置完成状态为true");
                isCompleted.set(true);
                doneLatch.countDown();
                
                // 输出统计信息
                logFrameStatistics();
                return;
            }
            
            // 转发JSON消息到前端
            if (userSession.isOpen()) {
                userSession.getAsyncRemote().sendText(message);
                logger.debug("JSON消息已转发到前端");
            } else {
                logger.warn("前端会话已关闭，无法转发JSON消息");
            }
            
        } catch (Exception e) {
            logger.error("处理JSON消息异常: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void onMessage(ByteBuffer bytes) {
        try {
            // 统计视频帧信息
            long currentFrame = frameCount.incrementAndGet();
            long currentSize = totalFrameSize.addAndGet(bytes.remaining());
            
            // 添加到缓冲队列而不是直接发送
            boolean success = frameBuffer.addFrame(bytes);
            
            if (success) {
                logger.debug("视频帧已加入缓冲队列: frame={}, size={}bytes, total={}bytes", 
                    currentFrame, bytes.remaining(), currentSize);
            } else {
                logger.warn("视频帧加入缓冲队列失败: frame={}", currentFrame);
            }
            
            // 定期输出队列状态
            if (currentFrame % 30 == 0) { // 每30帧输出一次状态
                VideoFrameBuffer.QueueStatus status = frameBuffer.getQueueStatus();
                logger.info("视频帧缓冲状态: {}", status);
            }
            
        } catch (Exception e) {
            logger.error("处理视频帧异常: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("连接关闭: code={}, reason={}, remote={}", code, reason, remote);
        
        // 停止视频帧缓冲器
        frameBuffer.stop();
        
        isCompleted.set(true);
        doneLatch.countDown();
    }
    
    @Override
    public void onError(Exception e) {
        logger.error("WebSocket错误: {}", e.getMessage(), e);
        
        // 停止视频帧缓冲器
        frameBuffer.stop();
        
        isCompleted.set(true);
        doneLatch.countDown();
    }
    
    public void resetStatus() {
        logger.debug("重置完成状态为false");
        isCompleted.set(false);
        doneLatch = new CountDownLatch(1);
    }
    
    public boolean awaitCompleted(long timeoutMillis) throws InterruptedException {
        return doneLatch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    public boolean isCompleted() {
        return isCompleted.get();
    }
    
    /**
     * 获取视频帧缓冲器状态
     */
    public VideoFrameBuffer.QueueStatus getBufferStatus() {
        return frameBuffer.getQueueStatus();
    }
    
    /**
     * 输出帧统计信息
     */
    private void logFrameStatistics() {
        long frames = frameCount.get();
        long totalSize = totalFrameSize.get();
        
        if (frames > 0) {
            double avgFrameSize = (double) totalSize / frames;
            logger.info("视频帧统计 - 总帧数: {}, 总大小: {}KB, 平均帧大小: {:.1f}KB", 
                frames, totalSize / 1024, avgFrameSize / 1024);
        }
    }
    
    /**
     * 优雅关闭
     */
    public void shutdown() {
        logger.info("开始优雅关闭WebSocket客户端: {}", sessionId);
        
        // 停止视频帧缓冲器
        frameBuffer.stop();
        
        // 关闭WebSocket连接
        if (isOpen()) {
            close();
        }
        
        logger.info("WebSocket客户端已关闭: {}", sessionId);
    }
}
