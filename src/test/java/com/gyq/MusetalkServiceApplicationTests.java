package com.gyq;

import com.gyq.config.ChatClientConfig;
import com.gyq.service.ModelService;
import com.gyq.service.MultiEmotionService.DouBaoTts;
import com.gyq.service.TtsService;
import com.gyq.service.dto.EmotionTextDto;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ChatClientConfig.class)
class MusetalkServiceApplicationTests {
	@Resource
	private TtsService ttsService;
	@Resource
	private ModelService modelService;
	@Resource
	private DouBaoTts douBaoTts;
	@Resource
	private

	@Test
	void ttsJsonCall() {
		String text = "你好，今天天气真不错。路上的车太多了搞得人很烦讷,幸好我能起飞。天太阴了害怕要下雨了，回家关窗户吧";
		ModelService modelService = new ModelService();
		List<EmotionTextDto> result = modelService.douBaoTtsJsonCall(text);
		System.out.println(result);
	}

	@Test
	void text2DouBaoAudio() throws Exception {
		String OUT_FILE = "src/main/resources/angry.mp3";
		DouBaoTts douBaoTts = new DouBaoTts();
		String text = "你好，这是东北御姐音色的 生气 情感的示例，很高兴为你服务.";
		byte[] audio = douBaoTts.douBaoAudioCallHttp(text, "angry", 5);
		Files.write(Paths.get(OUT_FILE), audio);
		System.out.println("Saved: " + OUT_FILE);
	}

	@Test
	void testChat() {
		String chatId = UUID.randomUUID().toString();
		// 第一轮
		String message = "你一个安全讲解员你叫小安，负责安全知识讲解";
		String answer = modelService.chatProcess(message, chatId);
		Assertions.assertNotNull(answer);
		// 第二轮
		message = "介绍一下你是谁，你叫什么";
		answer = modelService.chatProcess(message, chatId);
		Assertions.assertNotNull(answer);
		// 第三轮
		message = "我有十个苹果，我现在吃两个，我还剩几个，告诉我一下";
		answer = modelService.chatProcess(message, chatId);
		Assertions.assertNotNull(answer);
		// 第四轮
		message = "现在我再吃一个，你帮我计算一下还剩几个，告诉我一下";
		answer = modelService.chatProcess(message, chatId);
		Assertions.assertNotNull(answer);
	}


//	@Test
//	void testText2DouBaoAudio() throws Exception {
//		String text = "你好，今天天气真不错。路上的车太多了搞得人很烦讷,幸好我能起飞。天太阴了害怕要下雨了，回家关窗户吧";
//		CompletableFuture<List<FileDto>> res = ttsService.text2DouBaoAudio(text);
//		// 结果写成多个wav
//		res.get().forEach(path -> {
//			try {
//				byte[] bytes = Files.readAllBytes(path);
//				File file = new File(path.toString());
//				Files.write(file.toPath(), bytes);
//				System.out.println("Saved: " + file.getAbsolutePath());
//			} catch (Exception e) {
//				e.printStackTrace();
//			}
//		});
//	}


	// 辅助方法：检查 Base64 编码是否有效
	private boolean isValidBase64(String base64) {
		try {
			byte[] decoded = java.util.Base64.getDecoder().decode(base64);
			return decoded != null && decoded.length > 0;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

}
