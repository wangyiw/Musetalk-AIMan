package com.gyq.service;

import com.alibaba.fastjson.JSONPath;
import com.alibaba.nls.client.AccessToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;

@Service
public class AudioService {
    private AccessToken accessToken; // 新增：用于存储当前的AccessToken对象
    @Value("${alibaba.key}")
    private String key;
    @Value("${alibaba.secret}")
    private String secret;
    @Value("${alibaba.id}")
    private String id;
    @Value("${alibaba.url}")
    private String url;

    /**
     * 获取token
     */
    public String getToken() throws IOException {
        if (accessToken == null || System.currentTimeMillis() >= accessToken.getExpireTime()) { // 检查token是否过期
            accessToken = new AccessToken(id, secret);
            accessToken.apply();
        }
        System.out.println("过期时间"+accessToken.getExpireTime());
        return accessToken.getToken(); // 返回有效的token
    }

    /**
     * 根据音频路径获取文字
     *
     * @param fileName 音频路径
     * @return
     */
    public String process(String fileName) {
        // 1.转换音频为16000Hz
        String convertedFileName = convertTo16000Hz(fileName);
        if (convertedFileName == null) {
            System.err.println("音频转换失败!");
            return null;
        }


        /**
         * 设置HTTPS RESTful POST请求：
         * 1.使用HTTPS协议。
         * 2.语音识别服务域名：nls-gateway-cn-shanghai.aliyuncs.com。
         * 3.语音识别接口请求路径：/stream/v1/asr。
         * 4.设置必选请求参数：appkey、format、sample_rate。
         * 5.设置可选请求参数：enable_punctuation_prediction、enable_inverse_text_normalization、enable_voice_detection。
         */
        String request = url;
        request = request + "?appkey=" + key;
        request = request + "&format=" + "pcm";
        request = request + "&sample_rate=" + 16000;
        request = request + "&enable_punctuation_prediction=" + true;
        request = request + "&enable_inverse_text_normalization=" + true;
        request = request + "&enable_voice_detection=" + false;
        System.out.println("Request: " + request);

        /**
         * 设置HTTPS头部字段：
         * 1.鉴权参数。
         * 2.Content-Type：application/octet-stream。
         */
        HashMap<String, String> headers = new HashMap<String, String>();
        try {
            String token = getToken();
            headers.put("X-NLS-Token", token);
        } catch (IOException e) {
            System.out.println("获取Token失败");
        }

        headers.put("Content-Type", "application/octet-stream");

        /**
         * 发送HTTPS POST请求，返回服务端的响应。
         */
        String response = HttpUtil.sendPostFile(request, headers, convertedFileName);

        if (response != null) {
            System.out.println("Response: " + response);
            String result = JSONPath.read(response, "result").toString();
            System.out.println("识别结果：" + result);
            return result;
        } else {
            System.err.println("识别失败!");
        }
        return null;
    }

    private String convertTo16000Hz(String fileName) {
        try {
            // 输出文件路径
            String outputFileName = fileName.replace(".pcm", "_16000.pcm");

            // 构建 FFmpeg 命令
            String command = String.format("ffmpeg -f s16le -ar 48000 -ac 1 -i %s -ar 16000 -f s16le %s", fileName, outputFileName);

            // 执行命令
            Process process = Runtime.getRuntime().exec(command);

            // 获取 FFmpeg 的标准输出和标准错误流
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();

            // 读取标准输出流
            new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();

            // 读取标准错误流
            new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        error.append(line).append("\n");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();

            // 等待 FFmpeg 命令执行完毕
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("音频转换成功：" + outputFileName);
                System.out.println("FFmpeg 输出: " + output.toString());
                return outputFileName; // 返回转换后的文件路径
            } else {
                System.err.println("FFmpeg 转换音频失败，错误输出: " + error.toString());
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
