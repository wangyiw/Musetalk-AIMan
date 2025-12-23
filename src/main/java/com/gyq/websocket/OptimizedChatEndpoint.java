package com.gyq.websocket;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.gyq.FileDto;
import com.gyq.config.SpringContextUtil;
import com.gyq.service.AudioService;
import com.gyq.service.ModelService;
import com.gyq.service.TtsService;
import com.gyq.service.BlockingQueueService.SegmentedAudioProcessor;
import com.gyq.service.BlockingQueueService.VideoFrameManager;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 优化版ChatEndpoint - 使用阻塞队列实现分段音频处理
 */
@Component
@ServerEndpoint("/ws/chat/optimized/{token}")
public class OptimizedChatEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(OptimizedChatEndpoint.class);
    
    private static final Map<String, Session> sessions = new HashMap<>();
    private static final Map<String, ByteArrayOutputStream> audioBufferMap = new ConcurrentHashMap<>();
    
    // 分段音频处理器映射
    private static final Map<String, SegmentedAudioProcessor> processorMap = new ConcurrentHashMap<>();
    
    @Resource
    private AudioService audioService;
    @Resource
    private ModelService modelService;
    @Resource
    private TtsService ttsService;
    @Resource
    private VideoFrameManager videoFrameManager;
    
    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) throws IOException {
        // 初始化服务
        initializeServices();
        
        // 存储会话信息
        sessions.put(token, session);
        audioBufferMap.put(token, new ByteArrayOutputStream());
        
        // 创建视频帧缓冲器
        videoFrameManager.createBuffer(token, session);
        
        // 创建分段音频处理器
        try {
            URI museTalkUri = new URI("ws://192.168.10.101:8765");
            SegmentedAudioProcessor processor = new SegmentedAudioProcessor(session, token, museTalkUri);
            processor.start();
            processorMap.put(token, processor);
            
            logger.info("连接建立: token={}, session={}", token, session.getId());
            session.getBasicRemote().sendText("连接成功 - 使用优化版阻塞队列处理");
            
        } catch (URISyntaxException e) {
            logger.error("创建MuseTalk URI失败", e);
            session.getBasicRemote().sendText("连接失败: MuseTalk服务配置错误");
        }
    }
    
    /**
     * 初始化服务依赖
     */
    private void initializeServices() {
        if (audioService == null) {
            audioService = SpringContextUtil.getBean(AudioService.class);
        }
        if (modelService == null) {
            modelService = SpringContextUtil.getBean(ModelService.class);
        }
        if (ttsService == null) {
            ttsService = SpringContextUtil.getBean(TtsService.class);
        }
        if (videoFrameManager == null) {
            videoFrameManager = SpringContextUtil.getBean(VideoFrameManager.class);
        }
    }
    
    /**
     * 处理控制消息，例如音频结束标识
     */
    public String handleControlMessage(String message, @PathParam("token") String token) {
        String uuid = UUID.randomUUID().toString();
        logger.info("处理控制消息: token={}, uuid={}", token, uuid);

        if ("{\"type\": \"audio_end\"}".equals(message)) {
            logger.info("收到音频结束标识: {}", token);
            ByteArrayOutputStream buffer = audioBufferMap.get(token);
            if (buffer != null) {
                try {
                    String path = "/home/main/wyw/java/audio/" + uuid + ".pcm";
                    try (FileOutputStream fos = new FileOutputStream(path)) {
                        buffer.writeTo(fos);
                        logger.info("已保存[{}]的音频到: {}", token, path);
                    }
                    // 重置缓冲区
                    audioBufferMap.put(token, new ByteArrayOutputStream());
                    return path;
                } catch (IOException e) {
                    logger.error("写入音频文件失败: {}", e.getMessage(), e);
                }
            }
        }
        return null;
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("token") String token) throws Exception {
        logger.info("收到[{}]文本消息: {}", token, message);
        
        // 调用控制消息处理方法
        String path = handleControlMessage(message, token);
        if (ObjectUtils.isEmpty(path)) {
            return;
        }
        
        // 音频转文字
        String text = audioService.process(path);
        logger.info("音频转文字成功: {}", text);
        if (ObjectUtils.isEmpty(text)) {
            return;
        }

        // 将文本返还给前端
        String textUser = getResult("text_user", text);
        session.getBasicRemote().sendText(textUser);
        
        // 调用大模型
        String textModel = modelService.process(text);
        if (isValidJson(textModel)) {
            com.alibaba.fastjson2.JSONObject jsonObject = JSON.parseObject(textModel);
            if (jsonObject.containsKey("type")) {
                session.getBasicRemote().sendText(textModel);
                return;
            }
        }

        String textModelUser = getResult("text_assistant", textModel);
        session.getBasicRemote().sendText(textModelUser);
        
        // 生成音频段列表
        List<FileDto> audioList = ttsService.text2DouBaoAudio(textModel);
        
        // 使用阻塞队列方式处理音频列表
        processAudioListWithBlockingQueue(audioList, session, token);
        // 方式2：CountDownLatch方式（原有）
        // processAudioListWithCountDownLatch(audioList, session, token);
    }
    
    /**
     * 使用阻塞队列处理音频列表 - 核心优化方法
     */
    private void processAudioListWithBlockingQueue(List<FileDto> audioList, Session session, String token) {
        SegmentedAudioProcessor processor = processorMap.get(token);
        
        if (processor == null) {
            logger.error("未找到音频处理器: {}", token);
            try {
                session.getBasicRemote().sendText(getResult("error", "音频处理器未初始化"));
            } catch (IOException e) {
                logger.error("发送错误消息失败", e);
            }
            return;
        }
        
        logger.info("开始使用阻塞队列处理音频列表，共{}段", audioList.size());
        
        // 异步处理音频列表，避免阻塞WebSocket线程
        new Thread(() -> {
            try {
                // 获取处理器状态
                SegmentedAudioProcessor.ProcessorStatus status = processor.getStatus();
                logger.info("处理器状态: {}", status);
                
                // 处理音频列表 - 这里会按顺序一段一段处理
                processor.processAudioList(audioList);
                
                // 发送完成通知
                if (session.isOpen()) {
                    session.getAsyncRemote().sendText(getResult("process_completed", "所有音频段处理完成"));
                }
                
                logger.info("音频列表处理完成: token={}", token);
                
            } catch (Exception e) {
                logger.error("处理音频列表异常: token={}", token, e);
                try {
                    if (session.isOpen()) {
                        session.getAsyncRemote().sendText(getResult("error", "音频处理异常: " + e.getMessage()));
                    }
                } catch (Exception ex) {
                    logger.error("发送异常消息失败", ex);
                }
            }
        }, "AudioListProcessor-" + token).start();
    }
    
    /**
     * 接收 PCM 音频二进制数据
     */
    @OnMessage
    public void onBinaryMessage(ByteBuffer byteBuffer, Session session, @PathParam("token") String token) {
        logger.debug("收到[{}]音频数据: {} 字节", token, byteBuffer.remaining());
        ByteArrayOutputStream buffer = audioBufferMap.get(token);
        if (buffer != null) {
            try {
                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
                buffer.write(bytes);
            } catch (IOException e) {
                logger.error("写入音频数据异常: {}", e.getMessage(), e);
            }
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("token") String token) {
        // 清理会话
        sessions.remove(token);
        ByteArrayOutputStream buffer = audioBufferMap.remove(token);

        // 停止分段音频处理器
        SegmentedAudioProcessor processor = processorMap.remove(token);
        if (processor != null) {
            processor.stop();
            logger.info("已停止音频处理器: {}", token);
        }
        
        // 移除视频帧缓冲器
        videoFrameManager.removeBuffer(token);

        // 保存最后的音频数据
        if (buffer != null) {
            try {
                String path = "/home/main/wyw/tmp/" + token + ".pcm";
                try (FileOutputStream fos = new FileOutputStream(path)) {
                    buffer.writeTo(fos);
                    logger.info("已保存[{}]的音频到: {}", token, path);
                }
            } catch (IOException e) {
                logger.error("写入音频文件失败: {}", e.getMessage(), e);
            }
        }

        logger.info("连接关闭: token={}", token);
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam("token") String token) {
        logger.error("连接异常 [{}]: {}", token, error.getMessage(), error);
        
        // 清理资源
        SegmentedAudioProcessor processor = processorMap.remove(token);
        if (processor != null) {
            processor.stop();
        }
        videoFrameManager.removeBuffer(token);
    }
    
    /**
     * 检查JSON格式
     */
    public static boolean isValidJson(String str) {
        try {
            JSON.parse(str);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }
    
    /**
     * 构造结果JSON
     */
    public String getResult(String type, String content) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("type", type);
        jsonObject.put("content", content);
        return jsonObject.toJSONString();
    }
    
    /**
     * 获取系统状态 - 用于监控和调试
     */
    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("activeSessions", sessions.size());
        status.put("activeProcessors", processorMap.size());
        status.put("audioBuffers", audioBufferMap.size());
        
        // 获取视频帧管理器状态
        VideoFrameManager.SystemStatus frameStatus = videoFrameManager.getSystemStatus();
        status.put("videoFrameStatus", frameStatus.toString());
        
        // 获取各个处理器状态
        Map<String, String> processorStatuses = new HashMap<>();
        processorMap.forEach((token, processor) -> {
            processorStatuses.put(token, processor.getStatus().toString());
        });
        status.put("processorStatuses", processorStatuses);
        
        return status;
    }
}
