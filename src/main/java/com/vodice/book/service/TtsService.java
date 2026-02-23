package com.vodice.book.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodice.book.model.dto.ReadingScript;
import com.vodice.book.model.dto.ScriptSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Service
public class TtsService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${tts.api-url}")
    private String apiUrl;

    @Value("${tts.api-key}")
    private String apiKey;

    @Value("${tts.model}")
    private String model;

    @Value("${tts.output-format}")
    private String outputFormat;

    @Value("${app.storage.base-path}")
    private String storagePath;

    public TtsService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * voiceProfile -> QwenTTS 系统音色映射
     * QwenTTS 支持的音色: Cherry, Serena, Ethan, Chelsie 等
     */
    private static final Map<String, String> VOICE_MAP = Map.of(
            "narrator_default", "Ethan",
            "young_male_01", "Ethan",
            "young_female_01", "Cherry",
            "mature_male_01", "Ethan",
            "mature_female_01", "Serena",
            "child_01", "Chelsie");

    /**
     * 根据朗读稿合成完整章节音频
     *
     * @return 最终音频文件路径
     */
    public String synthesize(Long taskId, ReadingScript script,
            ProgressCallback progressCallback) throws IOException {
        List<ScriptSegment> segments = script.getSegments();
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("朗读稿没有段落数据");
        }

        // 构建 voiceProfile 映射
        Map<String, String> charVoiceMap = new HashMap<>();
        if (script.getCharacters() != null) {
            script.getCharacters().forEach(c -> charVoiceMap.put(c.getId(), c.getVoiceProfile()));
        }

        // 创建临时目录
        Path segmentsDir = Paths.get(storagePath, "audio", "segments", String.valueOf(taskId));
        Files.createDirectories(segmentsDir);

        Path outputDir = Paths.get(storagePath, "audio", "output");
        Files.createDirectories(outputDir);

        List<Path> segmentFiles = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            ScriptSegment seg = segments.get(i);
            String voiceProfile = charVoiceMap.getOrDefault(seg.getCharacterId(), "narrator_default");
            String voice = VOICE_MAP.getOrDefault(voiceProfile, "Cherry");

            log.info("合成段落 {}/{}: 角色={}, 音色={}, 情感={}, 文本长度={}",
                    i + 1, segments.size(), seg.getCharacterId(), voice,
                    seg.getEmotion(), seg.getText().length());

            // 调用 QwenTTS API 获取音频 URL
            String audioUrl = callQwenTtsApi(seg.getText(), voice);

            // 下载音频文件
            Path segmentFile = segmentsDir.resolve(String.format("%03d.wav", i));
            downloadAudio(audioUrl, segmentFile);
            segmentFiles.add(segmentFile);

            // 报告进度
            if (progressCallback != null) {
                int percent = (int) ((i + 1.0) / segments.size() * 100);
                progressCallback.onProgress(percent);
            }
        }

        // 拼接音频（固定使用 wav 格式，因为 QwenTTS 返回 WAV）
        Path outputFile = outputDir.resolve(taskId + ".wav");
        concatAudio(segmentFiles, outputFile);

        log.info("音频合成完成: {}", outputFile);
        return outputFile.toString();
    }

    /**
     * 调用 DashScope QwenTTS API
     * 返回生成的音频 URL
     */
    private String callQwenTtsApi(String text, String voice) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // DashScope 原生 API 格式
        Map<String, Object> input = new HashMap<>();
        input.put("text", text);
        input.put("voice", voice);
        input.put("language_type", "Chinese");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", input);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.debug("调用 QwenTTS API: model={}, voice={}", model, voice);

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("QwenTTS API 调用失败，状态码: " + response.getStatusCode());
        }

        // 解析响应，提取音频 URL
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String audioUrl = root.path("output").path("audio").path("url").asText();

            if (audioUrl == null || audioUrl.isEmpty()) {
                throw new RuntimeException("QwenTTS 返回的音频 URL 为空");
            }

            log.debug("QwenTTS 音频 URL: {}", audioUrl);
            return audioUrl;
        } catch (Exception e) {
            log.error("解析 QwenTTS 响应失败: {}", response.getBody(), e);
            throw new RuntimeException("解析 QwenTTS 响应失败", e);
        }
    }

    /**
     * 从 URL 下载音频文件
     */
    private void downloadAudio(String audioUrl, Path targetPath) throws IOException {
        // 使用 URI 直接下载，避免 RestTemplate 对 OSS 签名 URL 的参数二次编码
        try (InputStream in = new java.net.URI(audioUrl).toURL().openStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("音频 URL 格式错误: " + audioUrl, e);
        }
        log.debug("音频已下载: {} ({} bytes)", targetPath, Files.size(targetPath));
    }

    /**
     * 使用 javax.sound 正确合并多个 WAV 文件（处理 WAV 文件头）
     */
    private void concatAudio(List<Path> segmentFiles, Path outputFile) throws IOException {
        if (segmentFiles.size() == 1) {
            Files.copy(segmentFiles.get(0), outputFile, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        try {
            // 读取第一个 WAV 获取音频格式
            AudioInputStream firstStream = AudioSystem.getAudioInputStream(segmentFiles.get(0).toFile());
            AudioFormat format = firstStream.getFormat();

            // 计算总帧数
            List<AudioInputStream> streams = new ArrayList<>();
            streams.add(firstStream);
            long totalFrames = firstStream.getFrameLength();

            for (int i = 1; i < segmentFiles.size(); i++) {
                AudioInputStream ais = AudioSystem.getAudioInputStream(segmentFiles.get(i).toFile());
                totalFrames += ais.getFrameLength();
                streams.add(ais);
            }

            // 合并所有流为一个 SequenceInputStream
            java.util.Enumeration<InputStream> enumeration = java.util.Collections.enumeration(
                    streams.stream().map(s -> (InputStream) s).toList());
            SequenceInputStream seqIn = new SequenceInputStream(enumeration);

            AudioInputStream combined = new AudioInputStream(seqIn, format, totalFrames);
            AudioSystem.write(combined, AudioFileFormat.Type.WAVE, outputFile.toFile());

            // 关闭所有流
            for (AudioInputStream ais : streams) {
                ais.close();
            }

            log.info("WAV 音频拼接完成: {} 个片段 -> {}", segmentFiles.size(), outputFile);
        } catch (UnsupportedAudioFileException e) {
            throw new RuntimeException("不支持的音频格式", e);
        }
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percent);
    }
}
