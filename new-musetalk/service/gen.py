#!/usr/bin/env python3
"""
生成两段博物馆讲解 .wav 音频：
- museum_happy.wav  快乐版
- museum_angry.wav  愤怒版

优先使用离线 pyttsx3；失败时回退到 gTTS（需网络）并用 pydub+ffmpeg 转为 wav。
"""

import os
import sys

HAPPY_TEXT = "欢迎来到博物馆，这里珍藏着人类悠久的历史与文化，请跟随我一起探索。"
# 通过标点与语气词强调“愤怒感”
ANGRY_TEXT = "欢迎来到博物馆！这里珍藏着人类悠久的历史与文化！请跟随我一起探索！"

OUT_HAPPY = "museum_happy.wav"
OUT_ANGRY = "museum_angry.wav"

def tts_pyttsx3(text: str, out_path: str, rate: int = 180):
    import pyttsx3
    engine = pyttsx3.init()
    # 根据系统可适度调速（数值越大越快）
    engine.setProperty('rate', rate)
    # 某些系统支持 voices，可自行选择中文女声/男声
    # voices = engine.getProperty('voices')
    # for v in voices: print(v.id)  # 调试用
    engine.save_to_file(text, out_path)
    engine.runAndWait()

def mp3_to_wav(mp3_path: str, wav_path: str):
    from pydub import AudioSegment
    audio = AudioSegment.from_file(mp3_path, format="mp3")
    audio.export(wav_path, format="wav")

def tts_gtts_to_wav(text: str, out_wav: str, tmp_mp3: str = "_tmp_gtts.mp3"):
    from gtts import gTTS
    tts = gTTS(text=text, lang="zh-cn", slow=False)
    tts.save(tmp_mp3)
    mp3_to_wav(tmp_mp3, out_wav)
    try:
        os.remove(tmp_mp3)
    except OSError:
        pass

def main():
    # 先尝试离线 pyttsx3
    tried_pyttsx3 = False
    try:
        print("尝试使用离线 pyttsx3 生成音频...")
        tts_pyttsx3(HAPPY_TEXT, OUT_HAPPY, rate=185)   # 快乐稍快
        tts_pyttsx3(ANGRY_TEXT, OUT_ANGRY, rate=215)   # 愤怒更快更急
        tried_pyttsx3 = True
        print(f"✅ 已生成：{OUT_HAPPY}, {OUT_ANGRY}（pyttsx3）")
    except Exception as e:
        print(f"⚠️  pyttsx3 失败：{e}")

    if not tried_pyttsx3:
        # 回退 gTTS（需网络）+ pydub+ffmpeg
        try:
            print("改用 gTTS + pydub 生成 .wav ...（需网络与 ffmpeg）")
            tts_gtts_to_wav(HAPPY_TEXT, OUT_HAPPY)
            tts_gtts_to_wav(ANGRY_TEXT, OUT_ANGRY)
            print(f"✅ 已生成：{OUT_HAPPY}, {OUT_ANGRY}（gTTS+ffmpeg）")
        except Exception as e:
            print(f"❌ gTTS 回退也失败：{e}")
            print("请检查网络（gTTS）或安装 ffmpeg（pydub 转换需要）。")
            sys.exit(1)

if __name__ == "__main__":
    main()
