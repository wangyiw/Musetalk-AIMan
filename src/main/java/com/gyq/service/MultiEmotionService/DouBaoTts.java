package com.gyq.service.MultiEmotionService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyq.service.dto.DouBaoWsDto;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 豆包【大模型语音合成接口调用 HTTP模式调用】
 * 生成推理用的音频
 */
@Component
public class DouBaoTts {

//    @Resource
//    private DouBaoConfig douBaoConfig;// 通过统一配置注入参数

    private static final String URL = "https://openspeech.bytedance.com/api/v1/tts";
    private static final String WS_URL = "wss://openspeech.bytedance.com/api/v3/tts/unidirectional/stream";
    private static final String ACCESS_TOKEN = "SZipsqvNAOSz0SE_IjN8IFYe8imxj30_"; // Header: Authorization: Bearer; <token>
    private static final String APP_ID = "2950085072";
    private static final String VOICE = "zh_female_gaolengyujie_emo_v2_mars_bigtts"; // 高冷御姐（多情感）
    private static final int RATE = 16000;        // 8k/16k/24k
//    private static final String OUT_FILE = "Output_file";
//    private static final String EMOTION = "angry";
    private static final ObjectMapper M = new ObjectMapper();

    /**
     * http调用豆包语音生成
     * @param text 文本
     * @param emotion 情感
     * @param emotionScale 情感强度
     * @return
     * @throws Exception
     */
    public byte[] douBaoAudioCallHttp(String text, String emotion, int emotionScale) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // --- 构造请求 JSON ---
        DouBaoWsDto dto = new DouBaoWsDto();
        DouBaoWsDto.App app = new DouBaoWsDto.App();
        app.setAppid(APP_ID);
        app.setToken(ACCESS_TOKEN);
        app.setCluster("volcano_tts");
        dto.setApp(app);

        DouBaoWsDto.User user = new DouBaoWsDto.User();
        String uid = "uid-" + UUID.randomUUID();
        user.setUid(uid);
        dto.setUser(user);

        String encoding = "wav";
        DouBaoWsDto.Audio audio = new DouBaoWsDto.Audio(VOICE, encoding, RATE, 1.0, 1.0, true, emotion, emotionScale, "zh");
        dto.setAudio(audio);
        // websocket operation 改成 submit
        DouBaoWsDto.TTSRequest req = new DouBaoWsDto.TTSRequest(UUID.randomUUID().toString(), text, "query","ssml");
        dto.setRequest(req);
        String body = M.writeValueAsString(dto);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer; " + ACCESS_TOKEN) // 注意分号
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("HTTP状态码：" + resp.statusCode());
        resp.headers().firstValue("X-Tt-Logid").ifPresent(id -> System.out.println("X-Tt-Logid: " + id));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP error " + resp.statusCode() + ": " + resp.body());
        }

        JsonNode json = M.readTree(resp.body());
        int code = json.path("code").asInt(-1);
        String message = json.path("message").asText();
        if (code != 3000) { // 3000 = Success
            throw new IOException("TTS error code=" + code + ", message=" + message + ", body=" + resp.body());
        }

        String base64 = json.path("data").asText(""); // 音频数据（Base64）
        if (base64.isEmpty()) {
            throw new IOException("Empty audio data. Body=" + resp.body());
        }
        byte[] audioBytes = Base64.getDecoder().decode(base64);

        // 可选：打印时长
        String durMs = json.path("addition").path("duration").asText("");
        if (!durMs.isEmpty()) {
            System.out.println("Duration: " + durMs + " ms");
        }
        return audioBytes;
    }

    /**
     * 豆包websocket调用方法
     * @param text
     * @param emotion
     * @param emotionScale
     * @return
     * @throws Exception
     */
    public byte[] douBaoAudioCallWs(String text, String emotion, int emotionScale) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // --- 构造请求 JSON ---
        DouBaoWsDto dto = new DouBaoWsDto();
        DouBaoWsDto.App app = new DouBaoWsDto.App();
        app.setAppid(APP_ID);
        app.setToken(ACCESS_TOKEN);
        app.setCluster("volcano_tts");
        dto.setApp(app);

        DouBaoWsDto.User user = new DouBaoWsDto.User();
        user.setUid("uid-123");
        dto.setUser(user);

        DouBaoWsDto.Audio audio = new DouBaoWsDto.Audio(VOICE, "mp3", RATE, 1.0, 1.0, true, emotion, emotionScale, "zh");
        dto.setAudio(audio);
        // websocket operation 改成 submit
        DouBaoWsDto.TTSRequest req = new DouBaoWsDto.TTSRequest(UUID.randomUUID().toString(), text, "submit",null);
        dto.setRequest(req);
        final String jsonBody = M.writeValueAsString(dto);

        TtsListener listener = new TtsListener();
        WebSocket ws = client.newWebSocketBuilder()
                .header("Authorization", "Bearer; " + ACCESS_TOKEN)
                .buildAsync(URI.create(WS_URL), listener)
                .join();

        // 3) 发送 JSON 文本帧
        ws.sendText(jsonBody, true).join();

        // 4) 等待接收完成（最多 120 秒，可按需调整）
        byte[] audioBytes = listener.await(120, TimeUnit.SECONDS);

        // 5) 关闭连接（尽量优雅关闭）
        try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join(); } catch (Exception ignore) {}

        return audioBytes;
    }

}
