import os
from moviepy import VideoFileClip
from pydub import AudioSegment


def extract_first_5s_audio(video_path, output_wav_path):
    """
    提取视频前5秒音频并保存为WAV文件

    参数:
        video_path (str): 输入视频文件路径
        output_wav_path (str): 输出WAV文件路径
    """
    try:
        # 1. 使用moviepy加载视频并提取音频
        video = VideoFileClip(video_path)
        audio = video.audio

        # 2. 截取前5秒音频
        audio = audio.subclip(0, min(5, video.duration))  # 处理视频不足5秒的情况

        # 3. 临时保存为MP3（moviepy直接保存WAV有问题）
        temp_mp3 = "temp_audio.mp3"
        audio.write_audiofile(temp_mp3, logger=None)

        # 4. 使用pydub转换为WAV并删除临时文件
        sound = AudioSegment.from_mp3(temp_mp3)
        sound.export(output_wav_path, format="wav")
        os.remove(temp_mp3)

        print(f"成功提取前5秒音频并保存到: {output_wav_path}")
        return True

    except Exception as e:
        print(f"处理失败: {str(e)}")
        return False


if __name__ == "__main__":
    # 使用示例
    input_video = r"D:\edge\dbyj.mp4"  # 替换为你的视频路径
    output_audio = r"D:\edge\dbyj_audio.wav"  # 输出文件路径

    extract_first_5s_audio(input_video, output_audio)