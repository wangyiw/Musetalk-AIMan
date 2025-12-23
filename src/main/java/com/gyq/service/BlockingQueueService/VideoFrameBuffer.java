package com.gyq.service.BlockingQueueService;

import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频帧缓冲器 - 使用阻塞队列优化视频帧传输
 */
public class VideoFrameBuffer {
    private static final Logger logger = LoggerFactory.getLogger(VideoFrameBuffer.class);
    
    // 视频帧数据结构
    public static class FrameData {
        private final ByteBuffer data;
        private final long timestamp;
        private final String sessionId;
        
        public FrameData(ByteBuffer data, String sessionId) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
            this.sessionId = sessionId;
        }
        
        public ByteBuffer getData() { return data; }
        public long getTimestamp() { return timestamp; }
        public String getSessionId() { return sessionId; }
    }
    
    // 阻塞队列配置
    private final BlockingQueue<FrameData> frameQueue;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final Session userSession;
    private final String sessionId;
    private Thread senderThread;
    
    // 配置参数
    private static final int QUEUE_CAPACITY = 100;  // 队列容量
    private static final int SEND_TIMEOUT_MS = 5000; // 发送超时
    private static final int MAX_RETRY_COUNT = 3;    // 最大重试次数
    
    public VideoFrameBuffer(Session userSession, String sessionId) {
        this.frameQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        this.userSession = userSession;
        this.sessionId = sessionId;
    }
    
    /**
     * 启动视频帧发送线程
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            senderThread = new Thread(this::processSendQueue, "VideoFrameSender-" + sessionId);
            senderThread.setDaemon(true);
            senderThread.start();
            logger.info("视频帧发送线程已启动: {}", sessionId);
        }
    }
    
    /**
     * 停止视频帧发送
     */
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            if (senderThread != null) {
                senderThread.interrupt();
            }
            frameQueue.clear();
            logger.info("视频帧发送线程已停止: {}", sessionId);
        }
    }
    
    /**
     * 添加视频帧到队列
     */
    public boolean addFrame(ByteBuffer frameData) {
        if (!isRunning.get()) {
            logger.warn("发送线程未运行，丢弃帧数据: {}", sessionId);
            return false;
        }
        
        FrameData frame = new FrameData(frameData, sessionId);
        
        // 非阻塞添加，队列满时丢弃最旧的帧
        if (!frameQueue.offer(frame)) {
            FrameData oldFrame = frameQueue.poll(); // 移除最旧的帧
            if (oldFrame != null) {
                logger.debug("队列已满，丢弃旧帧: {}", sessionId);
            }
            return frameQueue.offer(frame); // 重新尝试添加
        }
        
        return true;
    }
    
    /**
     * 处理发送队列的主循环
     */
    private void processSendQueue() {
        logger.info("开始处理视频帧发送队列: {}", sessionId);
        
        while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                // 从队列中取出帧数据，超时等待避免无限阻塞
                FrameData frame = frameQueue.poll(1000, TimeUnit.MILLISECONDS);
                
                if (frame != null) {
                    sendFrameWithRetry(frame);
                }
                
            } catch (InterruptedException e) {
                logger.info("视频帧发送线程被中断: {}", sessionId);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("处理视频帧发送异常: {}", sessionId, e);
            }
        }
        
        logger.info("视频帧发送队列处理结束: {}", sessionId);
    }
    
    /**
     * 带重试机制的帧发送
     */
    private void sendFrameWithRetry(FrameData frame) {
        int retryCount = 0;
        
        while (retryCount < MAX_RETRY_COUNT && isRunning.get()) {
            try {
                if (userSession.isOpen()) {
                    // 异步发送，避免阻塞
                    userSession.getAsyncRemote().sendBinary(frame.getData());
                    
                    // 记录发送统计
                    long latency = System.currentTimeMillis() - frame.getTimestamp();
                    if (latency > 100) { // 延迟超过100ms记录警告
                        logger.warn("视频帧发送延迟较高: {}ms, session: {}", latency, sessionId);
                    }
                    
                    return; // 发送成功，退出重试
                } else {
                    logger.warn("WebSocket会话已关闭，停止发送: {}", sessionId);
                    stop();
                    return;
                }
                
            } catch (Exception e) {
                retryCount++;
                logger.warn("视频帧发送失败，重试 {}/{}: {}", retryCount, MAX_RETRY_COUNT, sessionId, e);
                
                if (retryCount < MAX_RETRY_COUNT) {
                    try {
                        Thread.sleep(100 * retryCount); // 递增延迟重试
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        
        logger.error("视频帧发送最终失败，已达最大重试次数: {}", sessionId);
    }
    
    /**
     * 获取队列状态信息
     */
    public QueueStatus getQueueStatus() {
        return new QueueStatus(
            frameQueue.size(),
            QUEUE_CAPACITY,
            isRunning.get(),
            userSession.isOpen()
        );
    }
    
    /**
     * 队列状态信息
     */
    public static class QueueStatus {
        private final int queueSize;
        private final int queueCapacity;
        private final boolean isRunning;
        private final boolean sessionOpen;
        
        public QueueStatus(int queueSize, int queueCapacity, boolean isRunning, boolean sessionOpen) {
            this.queueSize = queueSize;
            this.queueCapacity = queueCapacity;
            this.isRunning = isRunning;
            this.sessionOpen = sessionOpen;
        }
        
        public int getQueueSize() { return queueSize; }
        public int getQueueCapacity() { return queueCapacity; }
        public boolean isRunning() { return isRunning; }
        public boolean isSessionOpen() { return sessionOpen; }
        public double getQueueUsage() { return (double) queueSize / queueCapacity; }
        
        @Override
        public String toString() {
            return String.format("QueueStatus{size=%d/%d(%.1f%%), running=%s, sessionOpen=%s}", 
                queueSize, queueCapacity, getQueueUsage() * 100, isRunning, sessionOpen);
        }
    }
}
