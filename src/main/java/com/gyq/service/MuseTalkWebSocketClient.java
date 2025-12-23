package com.gyq.service;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyq.FileDto;
import jakarta.websocket.Session;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.cglib.core.Block;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MuseTalkWebSocketClient extends WebSocketClient {
    private final Session userSession; // 前端连接
    private final AtomicBoolean isCompleted = new AtomicBoolean(false);
    private final AtomicInteger messageCount = new AtomicInteger(0); // 消息计数器

    public final ObjectMapper objectMapper = new ObjectMapper();
    private volatile CountDownLatch doneLatch = new CountDownLatch(1);
    private final BlockingQueue<FileDto> audioQueue = new LinkedBlockingQueue<>();

    public MuseTalkWebSocketClient(URI serverUri, Session userSession) {
        super(serverUri);
        this.userSession = userSession;
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        System.out.println("成功连接到MuseTalk服务端，握手状态: " + serverHandshake.getHttpStatus() + " " + serverHandshake.getHttpStatusMessage());
        System.out.println("WebSocket连接已建立，可以开始发送消息");
    }

    public void sendAudioRequest(String audioPath, String emotion) throws IOException {
        try {
            // 检查连接状态
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
            System.out.println("调用musetalk入参：" + json);
            send(json);
            isCompleted.set(false); // 重置完成状态
            System.out.println("音频请求已发送，等待处理完成...");
        } catch (Exception e) {
            System.err.println("Error sending message: " + e.getMessage());
            e.printStackTrace();
            this.close();
            throw e;
        }
    }

    @Override
    public void onMessage(String message) {
        int count = messageCount.incrementAndGet();
        System.out.println("接收到第" + count + "条json消息: " + message);
        // {"status": "processing", "audio_path": "/home/main/wyw/java/audio/000_happy.wav"}
        try {
            JSONObject jsonObject = JSONObject.parseObject(message);
            System.out.println("解析JSON成功，status字段值: " + jsonObject.getString("status"));
            
            // 处理结束标识
            if (jsonObject.getString("status") != null && jsonObject.getString("status").equals("completed")) {
                System.out.println("推理完成此段，设置完成状态为true");
                isCompleted.set(true);
                doneLatch.countDown();   // 唤醒等待
                System.out.println("收到 completed，完成状态=true");
                return;
            }
            
            if (userSession.isOpen()) {
                // 正常转发消息到前端
                userSession.getBasicRemote().sendText(message);
                System.out.println("消息已转发到前端");
            } else {
                System.out.println("前端会话已关闭，无法转发消息");
            }
        } catch (Exception e) {
            System.err.println("发送json异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean isCompleted() {
        return isCompleted.get();
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
        try {
            if (userSession.isOpen()) {
                userSession.getBasicRemote().sendBinary(bytes);
            }
        } catch (Exception e) {
            System.out.println("发送二进制数据异常: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int i, String s, boolean b) {
        System.out.printf("连接关闭: code=%d, reason=%s, remote=%b\n", i, s, b);
        isCompleted.set(true);
    }

    @Override
    public void onError(Exception e) {
        System.err.println("WebSocket错误: " + e.getMessage());
        e.printStackTrace();
        isCompleted.set(true);
    }

    public void resetStatus() {
        System.out.println("重置完成状态为false");
        isCompleted.set(false);
        doneLatch = new CountDownLatch(1);
    }
    public boolean awaitCompleted(long timeoutMillis) throws InterruptedException {
        return doneLatch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

//    // 添加连接状态检查方法
//    public boolean isConnected() {
//        return isOpen() && !isClosed();
//    }

//    // 测试方法：打印当前连接状态
//    public void printConnectionStatus() {
//        System.out.println("=== WebSocket连接状态 ===");
//        System.out.println("isOpen: " + isOpen());
//        System.out.println("isClosed: " + isClosed());
//        System.out.println("getReadyState: " + getReadyState());
//        System.out.println("isCompleted: " + isCompleted.get());
//        System.out.println("消息计数: " + messageCount.get());
//        System.out.println("========================");
//    }
}
