package com.gyq.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DouBaoWsDto {

    /** app / user / audio / request 与官方 JSON 对齐 */
    private App app;
    private User user;
    private Audio audio;

    @JsonProperty("request")
    private TTSRequest request;

    // ---------------- sub DTOs ----------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class App {
        private String appid;
        /** 文档说明：无实际鉴权作用，但必须非空；真正鉴权放在 Header: Authorization: Bearer; <token> */
        private String token;     // 建议固定传 "x"
        private String cluster;   // 一般为 "volcano_tts"
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        private String uid;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Audio {
        @JsonProperty("voice_type")
        private String voiceType;

        private String encoding;     // "mp3" / "ogg_opus" / "pcm"（WS 不支持 "wav"）
        private Integer rate;        // 8000/16000/24000

        @JsonProperty("speed_ratio")
        private Double speedRatio;   // 0.8~2.0，可不填

        @JsonProperty("loudness_ratio")
        private Double loudnessRatio; // 0.5~2.0，可不填

        @JsonProperty("enable_emotion")
        private Boolean enableEmotion;

        private String emotion;      // "happy" / "angry" 等（取决于音色支持）

        @JsonProperty("emotion_scale")
        private Integer emotionScale; // 1~5

        @JsonProperty("explicit_language")
        private String explicitLanguage; // 建议 "zh"（中文）

    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TTSRequest {
        @JsonProperty("reqid")
        private String reqId;        // UUIDv4，每次唯一
        private String text;

        /** HTTP 用 "query"；WebSocket 用 "submit" */
        private String operation;

        @JsonProperty("text_type")
        private String textType;     // 用 SSML 时填 "ssml"

    }
}
