package com.gyq.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gyq.config.ChatClientConfig;
import com.gyq.service.Enum.SystemPromptEnum;
import com.gyq.service.dto.EmotionTextDto;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelService {
    private final RestTemplate restTemplate = new RestTemplate();
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(ModelService.class);
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final String API_KEY = "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";
    @Resource
    private ChatClientConfig chatClientConfig;

    /**
     * 调用gpt-4-mini  模型 超快速度 值得拥有
     */

    public String process(String text) {
        // 统一枚举管理prompt
        String content = SystemPromptEnum.YZ_XIAOAN.getDesc();
        HttpEntity<Map<String, Object>> request = buildRequest(text,content);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(API_URL, request, String.class);
            String responseBody = response.getBody();

            // 解析 content 字段
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);
            return root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();

        } catch (Exception e) {
            System.err.println("请求或解析失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 持久化记忆对话文本转换
     * @param text
     * @return
     */
    public String chatProcess(String text,String chatId) {
        ChatResponse response = chatClientConfig.getChatClient().prompt()
                .user(text)
                .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID,chatId).param(ChatMemory.CONVERSATION_ID,10))
                .call().chatResponse();
        String content = response.getResult().getOutput().getText();
        logger.info("content:{}",content);
        return content;
    }
    public List<EmotionTextDto> douBaoTtsJsonCall(String text){
        // 统一枚举管理prompt
        String content = SystemPromptEnum.DOUBAO.getDesc();
        HttpEntity<Map<String, Object>> request = buildRequest(text,content);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(API_URL, request, String.class);
            String responseBody = response.getBody();

            // 解析 content 字段
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(responseBody);
            String contentStr =  root
                    .path("choices")
                    .get(0)
                    .path("message")
                    .path("content")
                    .asText();
            List<EmotionTextDto> jsonList = mapper.readValue(contentStr, new TypeReference<>() {});
            return jsonList;

        } catch (Exception e) {
            System.err.println("请求或解析失败: " + e.getMessage());
            return null;
        }
    }


    /**
     * 构造请求
     * @param text
     * @return
     */
    public HttpEntity<Map<String, Object>> buildRequest(String text,String content){
        Map<String, Object> payload = new HashMap<>();

        payload.put("model", "gpt-4o-mini");
        payload.put("stream", false);

        List<Map<String, String>> messages = new ArrayList<>();
        // system prompt
        messages.add(Map.of(
                "role", "system",
                "content", content));

        // user input
        messages.add(Map.of(
                "role", "user",
                "content", text));

        payload.put("messages", messages);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(API_KEY);
        // 返回一个HTTP请求体
        return new HttpEntity<>(payload, headers);
    }

}
