package com.gyq.service.MultiEmotionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
@Component
public class TtsListener implements WebSocket.Listener {
    private final ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream binMsgBuf = new ByteArrayOutputStream();
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Throwable error;
    private static final ObjectMapper M = new ObjectMapper();

    @Override public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        webSocket.request(1);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (last) {
            // 可能是首个 ACK 文本：{"code":3000,"operation":"submit","message":"Successfully submitted",...}
            try {
                JsonNode obj = M.readTree(data.toString());
                int code = obj.path("code").asInt(-1);
                if (code != -1 && code != 3000) {
                    error = new IllegalStateException("WS error: " + obj.toString());
                    done.countDown();
                }
            } catch (Exception e) {
                // 文本不是 JSON，无需处理
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        // 同一条“消息”可能被分多帧推；先拼起来
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        try {
            binMsgBuf.write(chunk);
            if (last) {
                processOneBinaryMessage(binMsgBuf.toByteArray());
                binMsgBuf.reset();
            }
        } catch (Exception e) {
            error = e;
            done.countDown();
        }
        webSocket.request(1);
        return null;
    }

    private void processOneBinaryMessage(byte[] frame) throws Exception {
        // 音频响应：前 4 字节为自定义头（大端）
        if (frame.length < 4) return;

        int word = ((frame[0] & 0xFF) << 24) | ((frame[1] & 0xFF) << 16) | ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        int type  = (word >> 20) & 0xF;  // 0b1011 => Audio-only server response
        int flags = (word >> 16) & 0xF;  // 0b0010/0b0011 => 最后一条

        if (type != 0b1011) {
            // 不是音频包（可能是错误包），可忽略或终止
            return;
        }

        int idx = 4;
        boolean hasSeq = (flags == 0b0001 || flags == 0b0010 || flags == 0b0011);
        boolean isLast = (flags == 0b0010 || flags == 0b0011);

        if (hasSeq) {
            if (frame.length < 8) return;
            // int seq = ByteBuffer.wrap(frame, idx, 4).getInt(); // 如需可解析
            idx += 4;
        }

        if (idx < frame.length) {
            audioOut.write(frame, idx, frame.length - idx);
        }

        if (isLast) {
            done.countDown();
        }
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        done.countDown();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override public void onError(WebSocket webSocket, Throwable err) {
        error = err;
        done.countDown();
    }

    byte[] await(long timeout, TimeUnit unit) throws Exception {
        boolean ok = done.await(timeout, unit);
        if (!ok) throw new RuntimeException("WS receive timeout");
        if (error != null) throw new RuntimeException("WS error", error);
        return audioOut.toByteArray();
    }
}
