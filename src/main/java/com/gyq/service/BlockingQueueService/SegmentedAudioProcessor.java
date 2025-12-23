package com.gyq.service.BlockingQueueService; 

import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gyq.FileDto;

import jakarta.websocket.Session;

/**
 * 分段音频处理器 - 使用阻塞队列实现音频分段顺序处理
 * 替代CountDownLatch机制，实现一段一段的推理处理
 */
public class SegmentedAudioProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SegmentedAudioProcessor.class);
    
    // 音频段处理结果
    public static class ProcessResult {
        private final boolean success;
        private final String message;
        private final FileDto audioSegment;
        
        public ProcessResult(boolean success, String message, FileDto audioSegment) {
            this.success = success;
            this.message = message;
            this.audioSegment = audioSegment;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public FileDto getAudioSegment() { return audioSegment; }
        
        public static ProcessResult success(FileDto audioSegment) {
            return new ProcessResult(true, "处理成功", audioSegment);
        }
        
        public static ProcessResult failure(String message, FileDto audioSegment) {
            return new ProcessResult(false, message, audioSegment);
        }
    }
    
    // 音频段任务
    public static class AudioSegmentTask {
        private final FileDto audioSegment;
        private final int segmentIndex;
        private final BlockingQueue<ProcessResult> resultQueue;
        
        public AudioSegmentTask(FileDto audioSegment, int segmentIndex) {
            this.audioSegment = audioSegment;
            this.segmentIndex = segmentIndex;
            this.resultQueue = new LinkedBlockingQueue<>(1); // 容量为1，只存储一个结果
        }
        
        public FileDto getAudioSegment() { return audioSegment; }
        public int getSegmentIndex() { return segmentIndex; }
        public BlockingQueue<ProcessResult> getResultQueue() { return resultQueue; }
        
        // 设置处理结果
        public void setResult(ProcessResult result) {
            try {
                resultQueue.offer(result, 5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("设置处理结果被中断: segment={}", segmentIndex);
            }
        }
        
        // 等待处理结果
        public ProcessResult waitForResult(long timeoutSeconds) throws InterruptedException {
            return resultQueue.poll(timeoutSeconds, TimeUnit.SECONDS);
        }
    }
    
    private final Session userSession;
    private final String sessionId;
    private final URI museTalkUri;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    
    // 任务队列 - 存储待处理的音频段
    private final BlockingQueue<AudioSegmentTask> taskQueue = new LinkedBlockingQueue<>();
    
    // 处理线程
    private Thread processingThread;
    
    public SegmentedAudioProcessor(Session userSession, String sessionId, URI museTalkUri) {
        this.userSession = userSession;
        this.sessionId = sessionId;
        this.museTalkUri = museTalkUri;
    }
    
    /**
     * 启动分段处理器
     */
    public void start() {
        if (isProcessing.compareAndSet(false, true)) {
            processingThread = new Thread(this::processSegments, "AudioSegmentProcessor-" + sessionId);
            processingThread.setDaemon(true);
            processingThread.start();
            logger.info("分段音频处理器已启动: {}", sessionId);
        }
    }
    
    /**
     * 停止分段处理器
     */
    public void stop() {
        if (isProcessing.compareAndSet(true, false)) {
            if (processingThread != null) {
                processingThread.interrupt();
            }
            taskQueue.clear();
            logger.info("分段音频处理器已停止: {}", sessionId);
        }
    }
    
    /**
     * 处理音频段列表 - 主要接口方法
     */
    public void processAudioList(List<FileDto> audioList) {
        if (!isProcessing.get()) {
            logger.error("处理器未启动，无法处理音频列表");
            return;
        }
        
        logger.info("开始处理音频列表，共{}段", audioList.size());
        
        for (int i = 0; i < audioList.size(); i++) {
            FileDto audioSegment = audioList.get(i);
            AudioSegmentTask task = new AudioSegmentTask(audioSegment, i);
            
            try {
                // 1. 先发送音频数据到前端
                sendAudioToFrontend(audioSegment);
                
                // 2. 将任务加入队列
                taskQueue.offer(task, 10, TimeUnit.SECONDS);
                
                // 3. 等待这一段处理完成
                ProcessResult result = task.waitForResult(60); // 60秒超时
                
                if (result == null) {
                    logger.error("音频段{}处理超时", i);
                    break;
                } else if (!result.isSuccess()) {
                    logger.error("音频段{}处理失败: {}", i, result.getMessage());
                    break;
                } else {
                    logger.info("音频段{}处理成功", i);
                }
                
            } catch (InterruptedException e) {
                logger.error("处理音频段{}被中断", i);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("处理音频段{}异常", i, e);
                break;
            }
        }
        
        logger.info("音频列表处理完成");
    }
    
    /**
     * 分段处理主循环 - 后台线程执行
     */
    private void processSegments() {
        logger.info("开始分段处理循环: {}", sessionId);
        
        OptimizedMuseTalkWebSocketClient client = null;
        
        try {
            // 创建WebSocket客户端
            client = new OptimizedMuseTalkWebSocketClient(museTalkUri, userSession, sessionId);
            
            // 连接并等待连接建立
            client.connect();
            try {
                waitForConnection(client);
            } catch (InterruptedException e) {
                logger.error("等待连接被中断: {}", sessionId);
                Thread.currentThread().interrupt();
                return;
            }
            
            while (isProcessing.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 从队列中取出任务，超时等待避免无限阻塞
                    AudioSegmentTask task = taskQueue.poll(5, TimeUnit.SECONDS);
                    
                    if (task != null) {
                        processSegment(client, task);
                    }
                    
                } catch (InterruptedException e) {
                    logger.info("分段处理线程被中断: {}", sessionId);
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("处理音频段异常: {}", sessionId, e);
                }
            }
            
        } finally {
            // 清理资源
            if (client != null && client.isOpen()) {
                client.shutdown();
            }
            logger.info("分段处理循环结束: {}", sessionId);
        }
    }
    
    /**
     * 处理单个音频段
     */
    private void processSegment(OptimizedMuseTalkWebSocketClient client, AudioSegmentTask task) {
        FileDto audioSegment = task.getAudioSegment();
        int segmentIndex = task.getSegmentIndex();
        
        logger.info("开始处理音频段{}: path={}, emotion={}", 
            segmentIndex, audioSegment.getPath(), audioSegment.getEmotion());
        
        try {
            // 重置客户端状态
            client.resetStatus();
            
            // 发送音频请求到MuseTalk
            client.sendAudioRequest(audioSegment.getPath(), audioSegment.getEmotion());
            
            // 等待处理完成 - 这里使用原有的CountDownLatch机制
            boolean completed = client.awaitCompleted(60_000);
            
            if (completed && client.isCompleted()) {
                // 处理成功
                task.setResult(ProcessResult.success(audioSegment));
                logger.info("音频段{}推理完成", segmentIndex);
            } else {
                // 处理超时或失败
                task.setResult(ProcessResult.failure("推理超时或失败", audioSegment));
                logger.error("音频段{}推理超时或失败", segmentIndex);
            }
            
        } catch (Exception e) {
            task.setResult(ProcessResult.failure("推理异常: " + e.getMessage(), audioSegment));
            logger.error("音频段{}推理异常", segmentIndex, e);
        }
    }
    
    /**
     * 发送音频数据到前端
     */
    private void sendAudioToFrontend(FileDto audioSegment) {
        try {
            if (userSession.isOpen()) {
                String audioMessage = getResult("audio", audioSegment.getBase64());
                userSession.getAsyncRemote().sendText(audioMessage);
                logger.debug("音频数据已发送到前端");
            }
        } catch (Exception e) {
            logger.error("发送音频数据到前端失败", e);
        }
    }
    
    /**
     * 等待WebSocket连接建立
     */
    private void waitForConnection(OptimizedMuseTalkWebSocketClient client) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        while (!client.isOpen()) {
            if (System.currentTimeMillis() - startTime > 10000) {
                throw new RuntimeException("连接MuseTalk服务超时");
            }
            Thread.sleep(100);
        }
        logger.info("成功连接到MuseTalk服务");
    }
    
    /**
     * 构造JSON结果
     */
    private String getResult(String type, String content) {
        return String.format("{\"type\":\"%s\",\"content\":\"%s\"}", type, content);
    }
    
    /**
     * 获取处理器状态
     */
    public ProcessorStatus getStatus() {
        return new ProcessorStatus(
            isProcessing.get(),
            taskQueue.size(),
            sessionId
        );
    }
    
    /**
     * 处理器状态信息
     */
    public static class ProcessorStatus {
        private final boolean isProcessing;
        private final int queueSize;
        private final String sessionId;
        
        public ProcessorStatus(boolean isProcessing, int queueSize, String sessionId) {
            this.isProcessing = isProcessing;
            this.queueSize = queueSize;
            this.sessionId = sessionId;
        }
        
        public boolean isProcessing() { return isProcessing; }
        public int getQueueSize() { return queueSize; }
        public String getSessionId() { return sessionId; }
        
        @Override
        public String toString() {
            return String.format("ProcessorStatus{processing=%s, queueSize=%d, sessionId=%s}", 
                isProcessing, queueSize, sessionId);
        }
    }
}
