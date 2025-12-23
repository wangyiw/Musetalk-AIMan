package com.gyq;

public class FileDto {
    /**
     * 音频base64
     */
    private String base64;
    /**
     * 音频的绝对路径
     */
    private String path;

    public String getEmotion() {
        return emotion;
    }

    public void setEmotion(String emotion) {
        this.emotion = emotion;
    }

    /**
     * 情绪标识
     */
    private String emotion;

    public String getBase64() {
        return base64;
    }

    public void setBase64(String base64) {
        this.base64 = base64;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public String toString() {
        return "FileDto{" +
                "base64='" + base64 + '\'' +
                ", path='" + path + '\'' +
                '}';
    }
}
