package com.gyq.websocket;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.gyq.FileDto;
import com.gyq.config.SpringContextUtil;
import com.gyq.service.AudioService;
import com.gyq.service.ModelService;
import com.gyq.service.MuseTalkWebSocketClient;
import com.gyq.service.TtsService;
import jakarta.annotation.Resource;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@Component
@ServerEndpoint("/ws/chat/{token}")

public class ChatEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(ChatEndpoint.class);
    private static final Map<String, Session> sessions = new HashMap<>();
//    private volatile CountDownLatch doneLatch = new CountDownLatch(1);
    private static final Map<String, ByteArrayOutputStream> audioBufferMap = new ConcurrentHashMap<>();

//    @Value("${audioPath}")
//    private String audioPath;
    @Resource
    private AudioService audioService;
    @Resource
    private ModelService modelService;
    @Resource
    private TtsService ttsService;

    @OnOpen
    public void onOpen(Session session, @PathParam("token") String token) throws IOException {
        if (audioService == null) {
            audioService = SpringContextUtil.getBean(AudioService.class);
        }
        if (modelService == null) {
            modelService = SpringContextUtil.getBean(ModelService.class);
        }
        if (ttsService == null) {
            ttsService = SpringContextUtil.getBean(TtsService.class);
        }
        sessions.put(token, session);
        audioBufferMap.put(token, new ByteArrayOutputStream());
        logger.info("连接建立: token={}, session={}", token, session.getId());
        session.getBasicRemote().sendText("连接成功");
    }

    /**
     * 处理控制消息，例如音频结束标识
     */
    public String handleControlMessage(String message, @PathParam("token") String token) {
        String uuid = UUID.randomUUID().toString();
        logger.info(uuid);

        if ("{\"type\": \"audio_end\"}".equals(message)) {
            logger.info("收到音频结束标识: {}", token);
            ByteArrayOutputStream buffer = audioBufferMap.get(token);
            if (buffer != null) {
                try {
                    String path = "/home/main/wyw/java/audio/" + uuid + ".pcm";
//                    String path = "D:\\paeleap\\musetalk_java\\src\\main\\resources\\audio" + uuid + ".pcm";
//                    String path = "/Users/guoyunquan/Desktop/work/halo/" + uuid + ".pcm";
                    try (FileOutputStream fos = new FileOutputStream(path)) {
                        buffer.writeTo(fos);
                        System.out.println("已保存[" + token + "]的音频到: " + path);
                    }
                    // 清理缓冲区
//                    audioBufferMap.remove(token);
                    audioBufferMap.put(token, new ByteArrayOutputStream());
                    return path;
                } catch (IOException e) {
                    System.err.println("写入音频文件失败: " + e.getMessage());
                }
            }
        }
        return null;
    }

    @OnMessage
    public void onMessage(String message, Session session, @PathParam("token") String token) throws Exception {
        logger.info("收到[ {} ]文本消息: {}", token, message);
        // 调用新的控制消息处理方法
        String path = handleControlMessage(message, token);
        if (ObjectUtils.isEmpty(path)) {
            return;
        }
        //调用音频转文字的方法
        String text = audioService.process(path);
        System.out.println("文本转文字成功: " + text);
        if (ObjectUtils.isEmpty(text)) {
            return;
        }

        //将文本返还给前端
        String textUser = getResult("text_user", text);
        session.getBasicRemote().sendText(textUser);
        //用文本调用大模型
        String textModel = modelService.process(text);
        if (isValidJson(textModel)){
            com.alibaba.fastjson2.JSONObject jsonObject = JSON.parseObject(textModel);
            if (jsonObject.containsKey("type")){
                session.getBasicRemote().sendText(textModel);
                return;
            }
        }

        String textModelUser = getResult("text_assistant", textModel);
        session.getBasicRemote().sendText(textModelUser);
//        FileDto dto = ttsService.textToSpeechBase64(textModelUser);
        // 生成音频
        List<FileDto> audioList = ttsService.text2DouBaoAudio(textModel);

        processAudioList(audioList,session,token);


    }
    public static boolean isValidJson(String str) {
        try {
            JSON.parse(str);  // 可以换成 JSON.parseObject(str) 具体看你用途
            return true;
        } catch (JSONException e) {
            return false;
        }
    }
    public String getResult(String type, String content) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("type", type);
        jsonObject.put("content", content);
        return jsonObject.toJSONString();
    }

    /**
     * 接收 PCM 音频二进制数据
     */
    @OnMessage
    public void onBinaryMessage(ByteBuffer byteBuffer, Session session, @PathParam("token") String token) {
        logger.info("收到[" + token + "]音频数据: " + byteBuffer.remaining() + " 字节");
        ByteArrayOutputStream buffer = audioBufferMap.get(token);
        if (buffer != null) {
            try {
                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
                buffer.write(bytes);
            } catch (IOException e) {
                System.err.println("写入音频数据异常: " + e.getMessage());
            }
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("token") String token) {
        sessions.remove(token);
        ByteArrayOutputStream buffer = audioBufferMap.remove(token);

        if (buffer != null) {
            try {
//                String path = "/tmp/" + token + ".pcm";
                String path = "/home/main/wyw/tmp/" + token + ".pcm";
                try (FileOutputStream fos = new FileOutputStream(path)) {
                    buffer.writeTo(fos);
                    System.out.println("已保存[" + token + "]的音频到: " + path);
                }
            } catch (IOException e) {
                System.err.println("写入音频文件失败: " + e.getMessage());
            }
        }

        System.out.println("连接关闭: token=" + token);
    }

    @OnError
    public void onError(Session session, Throwable error, @PathParam("token") String token) {
        System.err.println("连接异常 [" + token + "]: " + error.getMessage());
    }



    private void processAudioList(List<FileDto> audioList, Session session, String token) {
        try {
            URI uri = new URI("ws://192.168.10.101:8765");
            System.out.println("准备连接到MuseTalk服务: " + uri);

            MuseTalkWebSocketClient client = new MuseTalkWebSocketClient(uri, session);

            // 连接并等待连接建立
            System.out.println("开始连接MuseTalk服务...");
            client.connect();

            // 等待连接建立，最多等待10秒
            long connectStartTime = System.currentTimeMillis();
            while (!client.isOpen()) {
                if (System.currentTimeMillis() - connectStartTime > 10000) {
                    throw new RuntimeException("连接MuseTalk服务超时，10秒内未建立连接");
                }
            }
            System.out.println("成功连接到MuseTalk服务，连接状态: " + client.getReadyState());

            if (!client.isOpen()) {
                throw new RuntimeException("连接建立后立即断开，无法继续处理");
            }
            for (FileDto dto : audioList) {
                try {
                    // 1. 先把音频文本回传给前端
                    session.getBasicRemote().sendText(getResult("audio", dto.getBase64()));

                    // 2. 发送给 musetalk
                    client.resetStatus();
                    System.out.println("准备发送音频请求: " + dto.getPath() + ", 情感: " + dto.getEmotion());
                    client.sendAudioRequest(dto.getPath(), dto.getEmotion());

                    // 3. 等待 completed（60s 自行调整）
                    boolean ok = client.awaitCompleted(60_000);
                    if (!ok) throw new RuntimeException("等待 completed 超时");

                    System.out.println("数字人生成图片完成，准备下一段");
                    if(client.isCompleted()){
                        System.out.println("数字人生成图片完成");
                    }
                } catch (Exception e) {
                    logger.error("图片生成失败: " + e.getMessage(), e);
                    break;
                }
            }
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }


//            for (FileDto dto : audioList) {
//                try {
//                    // 1. 发送音频数据
//                    session.getAsyncRemote().sendText(getResult("audio", dto.getBase64()));
//                    System.out.println("发送音频数据给musetalk");
//
//                    // 2. 发送音频请求
//                    System.out.println("准备发送音频请求: " + dto.getPath() + ", 情感: " + dto.getEmotion());
//                    client.sendAudioRequest(dto.getPath(), dto.getEmotion());
//                    System.out.println("音频请求已发送，等待处理完成...");
//
//                    // 3. 阻塞等待结束标识
//                    long startTime = System.currentTimeMillis();
////                    while (!client.isCompleted()) {
////                        if (System.currentTimeMillis() - startTime > 30000) { // 30秒超时
////                            throw new RuntimeException("处理超时");
////                        }
////
////                        // 检查连接状态
////                        if (!client.isOpen()) {
////                            throw new RuntimeException("WebSocket连接已断开");
////                        }
////
//////                        Thread.sleep(100); // 添加短暂休眠，避免CPU占用过高
////                        System.out.println("等待数字人生成图片中... 当前完成状态: " + client.isCompleted() + ", 连接状态: " + client.getReadyState());
////                    }
//                    System.out.println("数字人生成图片完成,准备下一段");
//                    client.resetStatus();
//
//                } catch (Exception e) {
//                    logger.error("图片生成失败: " + e.getMessage(), e);
//                    break;
//                }
//            }
//            System.out.println("所有图片生成完成");
//
//            // 关闭连接
//            if (client.isOpen()) {
//                client.close();
//            }
//
//        } catch (URISyntaxException | InterruptedException e) {
//            logger.error("处理音频列表失败", e);
//            throw new RuntimeException(e);
//        }
//    }

}
