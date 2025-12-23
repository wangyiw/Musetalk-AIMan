package com.gyq.service;

import com.gyq.FileDto;
import com.gyq.service.MultiEmotionService.DouBaoTts;
import com.gyq.service.dto.EmotionTextDto;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;


import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 音频业务
 * 调ws的时候：传的json入参可以是
 {
 "audio_path": "path/to/audio/file.wav",
 "options": {
 "jpeg_quality": 80,
 "batch_send": true,
 "verbose": true
 },
 "avatar_choice": "avatar_angry"  // 选择的 avatar
 }
 *
 */
@Service
public class TtsService {

    @Resource
    private ModelService modelService;
    @Resource
    private DouBaoTts douBaoTts;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    private static final String outputDir = "/home/main/wyw/java/audio/";

    public FileDto textToSpeechBase64(String text) {
        String url = "https://audio-suite.host.paeleap.com/task/tts/direct";

        // 构造请求体
        Map<String, Object> args = new HashMap<>();
        args.put("vcn", "zhixiaoxia");
        args.put("speed", 50);

        Map<String, Object> payload = new HashMap<>();
        payload.put("text", text);
        payload.put("provider", "AliTts");
        payload.put("args", args);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(payload, headers);
//        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", Integer.parseInt("20171")));
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
//        requestFactory.setProxy(proxy);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
//        RestTemplate restTemplate = new RestTemplate();

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    byte[].class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                byte[] audioBytes = response.getBody();
                // 保存为本地文件
                String filename = UUID.randomUUID() + ".wav";
//                File file = new File("/Users/guoyunquan/Desktop/work/halo/", filename);
                File file = new File("/home/paeleap/java/audio/", filename);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(audioBytes);
                }

                // 封装返回值
                FileDto fileDto = new FileDto();
                fileDto.setBase64(Base64.getEncoder().encodeToString(audioBytes));
                fileDto.setPath(file.getAbsolutePath());
                System.out.println(fileDto.getPath());
                return fileDto;
            } else {
                System.err.println("TTS请求失败，状态码：" + response.getStatusCode());
            }
        } catch (Exception e) {
            System.err.println("TTS请求异常: " + e.getMessage());
        }

        return null;
    }

    /**
     * 多线程调用豆包tts生成
     * @param text
     * @return
     * @throws Exception
     */
    public List<FileDto> text2DouBaoAudio(String text) throws Exception {
        // 1. 调用gpt-4-mini拿到json列表
        List<EmotionTextDto> resList = modelService.douBaoTtsJsonCall(text);
        // 2. 结果集合，按索引顺序存
//        Path[] paths = new Path[resList.size()];
        FileDto[] res = new FileDto[resList.size()];

        Files.createDirectories(Path.of(outputDir));
        CompletableFuture<?>[] futures = new CompletableFuture<?>[resList.size()];
        // 3.遍历返回的dto列表，用i标识顺序
        for (int i = 0; i < resList.size(); i++) {
            final int idx = i;
            final EmotionTextDto dto = resList.get(i);
            // 4. 并发调用豆包tts,接受到返回的音频数据
             futures[idx] = CompletableFuture.supplyAsync(() -> {
                        try {
                            return douBaoTts.douBaoAudioCallHttp(dto.getText(), dto.getEmotion(), 5);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, taskExecutor)
                    .orTimeout(20, TimeUnit.SECONDS)
                     // 拿到结果后消费，写入本地文件
                    .thenAccept(audioBytes -> {
                        String fileName = String.format("%03d_%s.wav", idx, dto.getEmotion());
                        Path output = Path.of(outputDir, fileName);
                        try {
                            Files.write(output, audioBytes);
                        } catch (IOException e) {
                            throw new RuntimeException("文件保存失败" ,e);
                        }
                        // FileDto
                        FileDto fileDto = new FileDto();
                        fileDto.setPath(output.toString());
                        fileDto.setBase64(Base64.getEncoder().encodeToString(audioBytes));
                        fileDto.setEmotion(dto.getEmotion());
                        res[idx] = fileDto;
                    });
        }
        // 取到completableFuture数组中的所有任务,等待所有任务完成,收集结果
        CompletableFuture.allOf(futures).join();
        System.out.println("所有任务完成,音频数量" + futures.length);
        return Arrays.asList(res);
    }


}

