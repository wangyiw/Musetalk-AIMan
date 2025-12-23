package com.gyq.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Component
@ConfigurationProperties(prefix = "doubao.tts")
public class DouBaoConfig {
    /**
     * 地址
     */
    private String url;
    /**
     * websocket地址
     */
    private String wsUrl;
    /**
     * 访问令牌
     */
    private String accessToken;
    /**
     * appId
     */
    private String appId;
    /**
     * 声音
     */
    private String voice;
    /**
     * 采样率
     */
    private Integer rate;

    public void setUrl(String url) {
        this.url = url;
    }

    public void setWsUrl(String wsUrl) {
        this.wsUrl = wsUrl;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public void setRate(Integer rate) {
        this.rate = rate;
    }



}
