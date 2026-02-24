"""
QwenTTS 音色验证与样本生成脚本
逐个验证音色可用性并生成试听音频样本
"""
import requests
import json
import time
import os
import sys

API_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
API_KEY = "sk-9cbcb628e4934abcbcb2e9cb913a1940"
OUTPUT_DIR = r"d:\JavaProject\VodiceBook\src\main\resources\static\voice-samples"

SAMPLE_TEXT = "大家好，我是一名有声书朗读者，欢迎收听精彩故事。"

# 从多个来源整理的候选音色列表
CANDIDATES = [
    # 302.AI 官方文档的 13 个
    "Cherry", "Ethan", "Nofish", "Jennifer", "Ryan", "Katerina",
    "Elias", "Jada", "Dylan", "Sunny", "Li", "Marcus", "Roy",
    # 搜索引擎补充的
    "Serena", "Chelsie", "Vivian", "Bella", "Dolce", "Kiki",
    "Lenn", "Nini", "Stella", "Sohee", "Ono Anna", "Momo",
    "Vincent", "Uncle_Fu", "Eric", "Aiden",
    # 额外可能的
    "Adam", "Mason", "Jacob", "William", "James",
    "Emma", "Olivia", "Sophia", "Isabella", "Chloe", "Mia",
    "Michael", "Benjamin", "Lucas",
]

os.makedirs(OUTPUT_DIR, exist_ok=True)

headers = {
    "Authorization": f"Bearer {API_KEY}",
    "Content-Type": "application/json"
}

supported = []
failed = []

for name in CANDIDATES:
    data = {
        "model": "qwen3-tts-flash",
        "input": {"text": SAMPLE_TEXT, "voice": name}
    }
    try:
        resp = requests.post(API_URL, headers=headers, json=data, timeout=30)
        if resp.status_code == 200:
            result = resp.json()
            audio_url = result.get("output", {}).get("audio", {}).get("url")
            if audio_url:
                # 下载音频到本地
                audio_resp = requests.get(audio_url, timeout=30)
                if audio_resp.status_code == 200:
                    safe_name = name.replace(" ", "_")
                    filepath = os.path.join(OUTPUT_DIR, f"{safe_name}.wav")
                    with open(filepath, "wb") as f:
                        f.write(audio_resp.content)
                    supported.append(name)
                    print(f"OK: {name} -> {filepath}")
                else:
                    failed.append(name)
                    print(f"DOWNLOAD_FAIL: {name}")
            else:
                failed.append(name)
                print(f"NO_URL: {name} -> {json.dumps(result, ensure_ascii=False)[:100]}")
        else:
            failed.append(name)
            err = resp.text[:120]
            print(f"FAIL: {name} -> {err}")
    except Exception as e:
        failed.append(name)
        print(f"ERROR: {name} -> {e}")
    
    time.sleep(0.8)  # 限流

print(f"\n=== RESULT ===")
print(f"Supported ({len(supported)}): {supported}")
print(f"Failed ({len(failed)}): {failed}")
