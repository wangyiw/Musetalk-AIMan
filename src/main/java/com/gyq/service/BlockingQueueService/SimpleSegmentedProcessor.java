package com.gyq.service.BlockingQueueService;

import com.gyq.FileDto;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 简化版分段音频处理器
 * 直接用阻塞队列替代CountDownLatch实现分段顺序处理
 */
public class SimpleSegmentedProcessor {
    private static final Logger logger = LoggerFactory.getLogger(SimpleSegmentedProcessor.class);
    
    private final Session userSession;
    private final String sessionId;
    
    // 完成信号队列 - 替代CountDownLatch
    private final BlockingQueue<String> completionQueue = new LinkedBlockingQueue<>();
    
    public SimpleSegmentedProcessor(Session userSession, String sessionId) {
        this.userSession = userSession;
        this.sessionId = sessionId;
    }
    
    /**
     * 处理音频列表 - 用阻塞队列实现分段顺序处理
     */
    public void processAudioList(List<FileDto> audioList) throws Exception {
        URI uri = new URI("ws://192.168.10.101:8765");
        logger.info("准备连接到MuseTalk服务: {}", uri);
        
        // 创建优化版WebSocket客户端，但修改为使用阻塞队列
        BlockingQueueMuseTalkClient client = new BlockingQueueMuseTalkClient(uri, userSession, sessionId, completionQueue);
        
        // 连接并等待连接建立
        logger.info("开始连接MuseTalk服务...");
        client.connect();
        
        // 等待连接建立
        long connectStartTime = System.currentTimeMillis();
        while (!client.isOpen()) {
            if (System.currentTimeMillis() - connectStartTime > 10000) {
                throw new RuntimeException("连接MuseTalk服务超时，10秒内未建立连接");
            }
            Thread.sleep(100);
        }
        logger.info("成功连接到MuseTalk服务，连接状态: {}", client.getReadyState());
        
        try {
            // 逐段处理音频
            for (int i = 0; i < audioList.size(); i++) {
                FileDto dto = audioList.get(i);
                logger.info("开始处理第{}段音频: path={}, emotion={}", i + 1, dto.getPath(), dto.getEmotion());
                
                // 1. 发送音频数据到前端
                sendAudioToFrontend(dto);
                
                // 2. 清空完成队列（确保队列干净）
                completionQueue.clear();
                
                // 3. 发送音频请求到MuseTalk
                client.sendAudioRequest(dto.getPath(), dto.getEmotion());
                
                // 4. 使用阻塞队列等待完成信号 - 替代CountDownLatch.await()
                String completionSignal = completionQueue.poll(60, TimeUnit.SECONDS);
                
                if (completionSignal == null) {
                    throw new RuntimeException("第" + (i + 1) + "段音频处理超时");
                } else if ("completed".equals(completionSignal)) {
                    logger.info("第{}段音频处理完成", i + 1);
                } else if ("error".equals(completionSignal)) {
                    throw new RuntimeException("第" + (i + 1) + "段音频处理失败");
                }
            }
            
            logger.info("所有音频段处理完成，共{}段", audioList.size());
            
        } finally {
            // 关闭连接
            if (client.isOpen()) {
                client.shutdown();
            }
        }
    }
    
    /**
     * 发送音频数据到前端
     */
    private void sendAudioToFrontend(FileDto audioSegment) {
        try {
            if (userSession.isOpen()) {
                String audioMessage = String.format("{\"type\":\"audio\",\"content\":\"%s\"}", audioSegment.getBase64());
                userSession.getAsyncRemote().sendText(audioMessage);
                logger.debug("音频数据已发送到前端");
            }
        } catch (Exception e) {
            logger.error("发送音频数据到前端失败", e);
        }
    }
}
