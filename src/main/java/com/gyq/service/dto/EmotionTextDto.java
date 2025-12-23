package com.gyq.service.dto;

import lombok.Data;

@Data
public class EmotionTextDto {
    /**
     * 文本
     */
    private String text;
    /**
     * 情感 happy , fear , angry
     */
    private String emotion;

}
