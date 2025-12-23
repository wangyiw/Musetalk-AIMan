package com.gyq.service;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WebSocketTest {

    @Test
    public void testWebSocketConnection() throws Exception {
        // 测试连接到MuseTalk服务
        URI uri = new URI("ws://192.168.10.101:8765");
        CountDownLatch messageLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshakedata) {
                System.out.println("连接已建立，状态码: " + handshakedata.getHttpStatus());
                // 发送测试消息
                try {
                    Thread.sleep(1000); // 等待1秒确保连接稳定
                    String testMessage = "{\"test\": \"connection\"}";
                    System.out.println("发送测试消息: " + testMessage);
                    send(testMessage);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMessage(String message) {
                System.out.println("收到消息: " + message);
                messageLatch.countDown();
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("连接关闭: code=" + code + ", reason=" + reason + ", remote=" + remote);
                closeLatch.countDown();
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("连接错误: " + ex.getMessage());
                ex.printStackTrace();
            }
        };

        try {
            System.out.println("开始连接...");
            client.connect();
            
            // 等待连接建立
            long startTime = System.currentTimeMillis();
            while (!client.isOpen() && (System.currentTimeMillis() - startTime) < 10000) {
                Thread.sleep(100);
            }
            
            if (client.isOpen()) {
                System.out.println("连接成功建立");
                System.out.println("连接状态: " + client.getReadyState());
                
                // 等待消息或超时
                boolean messageReceived = messageLatch.await(30, TimeUnit.SECONDS);
                if (messageReceived) {
                    System.out.println("成功接收到消息");
                } else {
                    System.out.println("30秒内未收到消息");
                }
                
                // 等待连接关闭或超时
                boolean closed = closeLatch.await(10, TimeUnit.SECONDS);
                if (closed) {
                    System.out.println("连接已关闭");
                } else {
                    System.out.println("连接未关闭，手动关闭");
                    client.close();
                }
            } else {
                System.out.println("连接建立失败");
            }
            
        } finally {
            if (client.isOpen()) {
                client.close();
            }
        }
    }
}
