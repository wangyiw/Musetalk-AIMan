package com.gyq.service.BlockingQueueService;

import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 视频帧管理器 - 统一管理多个会话的视频帧缓冲
 */
@Service
public class VideoFrameManager {
    private static final Logger logger = LoggerFactory.getLogger(VideoFrameManager.class);
    
    // 存储每个会话的视频帧缓冲器
    private final ConcurrentHashMap<String, VideoFrameBuffer> bufferMap = new ConcurrentHashMap<>();
    
    // 定时任务执行器，用于监控和清理
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    public VideoFrameManager() {
        // 启动定时监控任务
        startMonitoringTasks();
    }
    
    /**
     * 为会话创建视频帧缓冲器
     */
    public VideoFrameBuffer createBuffer(String sessionId, Session userSession) {
        VideoFrameBuffer buffer = new VideoFrameBuffer(userSession, sessionId);
        buffer.start();
        
        VideoFrameBuffer oldBuffer = bufferMap.put(sessionId, buffer);
        if (oldBuffer != null) {
            logger.warn("会话{}已存在缓冲器，停止旧缓冲器", sessionId);
            oldBuffer.stop();
        }
        
        logger.info("为会话{}创建视频帧缓冲器", sessionId);
        return buffer;
    }
    
    /**
     * 获取会话的视频帧缓冲器
     */
    public VideoFrameBuffer getBuffer(String sessionId) {
        return bufferMap.get(sessionId);
    }
    
    /**
     * 移除会话的视频帧缓冲器
     */
    public void removeBuffer(String sessionId) {
        VideoFrameBuffer buffer = bufferMap.remove(sessionId);
        if (buffer != null) {
            buffer.stop();
            logger.info("移除会话{}的视频帧缓冲器", sessionId);
        }
    }
    
    /**
     * 获取所有活跃的缓冲器数量
     */
    public int getActiveBufferCount() {
        return bufferMap.size();
    }
    
    /**
     * 获取系统整体状态
     */
    public SystemStatus getSystemStatus() {
        int totalBuffers = bufferMap.size();
        int runningBuffers = 0;
        int totalQueueSize = 0;
        int totalCapacity = 0;
        
        for (VideoFrameBuffer buffer : bufferMap.values()) {
            VideoFrameBuffer.QueueStatus status = buffer.getQueueStatus();
            if (status.isRunning()) {
                runningBuffers++;
            }
            totalQueueSize += status.getQueueSize();
            totalCapacity += status.getQueueCapacity();
        }
        
        return new SystemStatus(totalBuffers, runningBuffers, totalQueueSize, totalCapacity);
    }
    
    /**
     * 启动监控任务
     */
    private void startMonitoringTasks() {
        // 每30秒输出系统状态
        scheduler.scheduleAtFixedRate(this::logSystemStatus, 30, 30, TimeUnit.SECONDS);
        
        // 每5分钟清理无效的缓冲器
        scheduler.scheduleAtFixedRate(this::cleanupInvalidBuffers, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * 输出系统状态日志
     */
    private void logSystemStatus() {
        if (bufferMap.isEmpty()) {
            return;
        }
        
        SystemStatus status = getSystemStatus();
        logger.info("视频帧系统状态: {}", status);
        
        // 如果队列使用率过高，输出警告
        if (status.getQueueUsage() > 0.8) {
            logger.warn("视频帧队列使用率过高: {:.1f}%，可能存在性能问题", status.getQueueUsage() * 100);
        }
    }
    
    /**
     * 清理无效的缓冲器
     */
    private void cleanupInvalidBuffers() {
        bufferMap.entrySet().removeIf(entry -> {
            String sessionId = entry.getKey();
            VideoFrameBuffer buffer = entry.getValue();
            VideoFrameBuffer.QueueStatus status = buffer.getQueueStatus();
            
            // 如果会话已关闭且缓冲器未运行，则清理
            if (!status.isSessionOpen() && !status.isRunning()) {
                logger.info("清理无效的视频帧缓冲器: {}", sessionId);
                buffer.stop();
                return true;
            }
            
            return false;
        });
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        logger.info("开始关闭视频帧管理器");
        
        // 停止所有缓冲器
        bufferMap.values().forEach(VideoFrameBuffer::stop);
        bufferMap.clear();
        
        // 关闭定时任务
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("视频帧管理器已关闭");
    }
    
    /**
     * 系统状态信息
     */
    public static class SystemStatus {
        private final int totalBuffers;
        private final int runningBuffers;
        private final int totalQueueSize;
        private final int totalCapacity;
        
        public SystemStatus(int totalBuffers, int runningBuffers, int totalQueueSize, int totalCapacity) {
            this.totalBuffers = totalBuffers;
            this.runningBuffers = runningBuffers;
            this.totalQueueSize = totalQueueSize;
            this.totalCapacity = totalCapacity;
        }
        
        public int getTotalBuffers() { return totalBuffers; }
        public int getRunningBuffers() { return runningBuffers; }
        public int getTotalQueueSize() { return totalQueueSize; }
        public int getTotalCapacity() { return totalCapacity; }
        public double getQueueUsage() { 
            return totalCapacity > 0 ? (double) totalQueueSize / totalCapacity : 0.0; 
        }
        
        @Override
        public String toString() {
            return String.format("SystemStatus{buffers=%d/%d, queue=%d/%d(%.1f%%)}", 
                runningBuffers, totalBuffers, totalQueueSize, totalCapacity, getQueueUsage() * 100);
        }
    }
}
