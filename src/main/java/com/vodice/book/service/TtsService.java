package com.vodice.book.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodice.book.model.dto.ReadingScript;
import com.vodice.book.model.dto.ScriptCharacter;
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
     */
    private static final Map<String, String> VOICE_MAP = Map.of(
            "narrator_default", "Ethan",
            "young_male_01", "Ethan",
            "young_female_01", "Cherry",
            "mature_male_01", "Ethan",
            "mature_female_01", "Serena",
            "child_01", "Chelsie");

    /**
     * 男女常用高表现力音色池（基于 DashScope API 真实验证）
     */
    private static final List<String> MALE_VOICES = List.of(
            "Ethan", "Ryan", "Roy", "Lenn", "Vincent", "Li", "Dolce", "Elias", "Dylan", "Marcus", "Eric", "Aiden",
            "Nofish");

    private static final List<String> FEMALE_VOICES = List.of(
            "Cherry", "Serena", "Vivian", "Jennifer", "Katerina", "Stella", "Kiki", "Nini", "Sohee", "Bella", "Momo",
            "Chelsie", "Mia", "Ono Anna", "Jada", "Sunny");

    /**
     * 根据朗读稿合成完整章节音频
     *
     * @return 最终音频文件路径
     */
    public String synthesize(Long userId, Long taskId, ReadingScript script,
            ProgressCallback progressCallback) throws IOException {
        List<ScriptSegment> segments = script.getSegments();
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("朗读稿没有段落数据");
        }

        // 构建角色映射（ID -> ScriptCharacter）
        Map<String, ScriptCharacter> charMap = new HashMap<>();
        if (script.getCharacters() != null) {
            script.getCharacters().forEach(c -> charMap.put(c.getId(), c));
        }

        // 创建临时目录
        Path segmentsDir = Paths.get(storagePath, String.valueOf(userId), "audio", "segments", String.valueOf(taskId));
        Files.createDirectories(segmentsDir);

        Path outputDir = Paths.get(storagePath, String.valueOf(userId), "audio", "output");
        Files.createDirectories(outputDir);

        List<Path> segmentFiles = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            ScriptSegment seg = segments.get(i);
            String voice = resolveVoice(seg, charMap);

            // 切分长段落文本，避免超过 API 支持的最大限制 (QwenTTS 600 字符限制，保守取 400)
            List<String> textChunks = splitText(seg.getText(), 400);

            for (int chunkIdx = 0; chunkIdx < textChunks.size(); chunkIdx++) {
                String chunk = textChunks.get(chunkIdx);
                log.info("合成段落 {}/{}: 角色={}, 音色={}, 情感={}, 类型={}/{}, 切片={}/{}, 文本长度={}",
                        i + 1, segments.size(), seg.getCharacterId(), voice,
                        seg.getEmotion(), seg.getType(), seg.getSubType(),
                        chunkIdx + 1, textChunks.size(), chunk.length());

                // 调用 QwenTTS API 获取音频 URL
                String audioUrl = callQwenTtsApi(chunk, voice);

                // 下载音频文件，用带副下标的名称保证顺序
                Path segmentFile = segmentsDir.resolve(String.format("%03d_%03d.wav", i, chunkIdx));
                downloadAudio(audioUrl, segmentFile);
                segmentFiles.add(segmentFile);
            }

            // 报告进度
            if (progressCallback != null) {
                int percent = (int) ((i + 1.0) / segments.size() * 100);
                progressCallback.onProgress(percent);
            }
        }

        // 拼接音频
        Path outputFile = outputDir.resolve(taskId + ".wav");
        concatAudio(segmentFiles, outputFile);

        log.info("音频合成完成: {}", outputFile);
        return outputFile.toString();
    }

    /**
     * 将长文本按照标点符号进行安全切分，确保单片长度不超过 maxLength
     */
    private List<String> splitText(String text, int maxLength) {
        if (text == null || text.trim().isEmpty())
            return List.of();
        if (text.length() <= maxLength)
            return List.of(text);

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        // 按照常见的中文/英文断句标点切分，并利用环视保留标点本身
        String[] sentences = text.split("(?<=[。！？；;\\n])");

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > maxLength) {
                // 当前收集的文字如果不为空，先提交为一段
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                // 如果单句本身异常长，强行按照 maxLength 进行硬切
                while (sentence.length() > maxLength) {
                    chunks.add(sentence.substring(0, maxLength));
                    sentence = sentence.substring(maxLength);
                }
            }
            currentChunk.append(sentence);
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 解析段落应使用的 TTS 音色
     * 优先级：用户手选 voice > voiceProfile 映射 > 基于角色 ID 的 gender 自动分配池 > 默认
     */
    private String resolveVoice(ScriptSegment seg, Map<String, ScriptCharacter> charMap) {
        ScriptCharacter character = charMap.get(seg.getCharacterId());

        // 0. 最高优先：用户手选的音色
        if (character != null && character.getVoice() != null && !character.getVoice().isBlank()) {
            return character.getVoice();
        }

        // 1. 尝试 voiceProfile 精确映射
        if (character != null && character.getVoiceProfile() != null) {
            String mapped = VOICE_MAP.get(character.getVoiceProfile());
            if (mapped != null) {
                return mapped;
            }
        }

        // 2. 旁白特殊处理
        if ("narrator".equals(seg.getCharacterId())) {
            return "Ethan";
        }

        // 3. 根据角色 ID 的 Hash 值，给相同性别角色分配稳定的独立音色
        if (character != null && character.getGender() != null) {
            // 取哈希的绝对值防止数组越界
            int hash = Math.abs(character.getId().hashCode());
            if ("female".equalsIgnoreCase(character.getGender())) {
                return FEMALE_VOICES.get(hash % FEMALE_VOICES.size());
            } else if ("male".equalsIgnoreCase(character.getGender())) {
                return MALE_VOICES.get(hash % MALE_VOICES.size());
            }
        }

        // 4. 未知角色用哈希在男性池里随便挑一个保证稳定
        int fallbackHash = Math.abs(seg.getCharacterId() != null ? seg.getCharacterId().hashCode() : 0);
        return MALE_VOICES.get(fallbackHash % MALE_VOICES.size());
    }

    /**
     * 调用 DashScope QwenTTS API
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

        // 添加 parameters 控制语速降低 15% (0.85)
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("rate", 0.85);
        requestBody.put("parameters", parameters);

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
        try (InputStream in = new java.net.URI(audioUrl).toURL().openStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("音频 URL 格式错误: " + audioUrl, e);
        }
        log.debug("音频已下载: {} ({} bytes)", targetPath, Files.size(targetPath));
    }

    /**
     * 使用 javax.sound 正确合并多个 WAV 文件
     */
    private void concatAudio(List<Path> segmentFiles, Path outputFile) throws IOException {
        if (segmentFiles.size() == 1) {
            Files.copy(segmentFiles.get(0), outputFile, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        try {
            AudioInputStream firstStream = AudioSystem.getAudioInputStream(segmentFiles.get(0).toFile());
            AudioFormat format = firstStream.getFormat();

            List<AudioInputStream> streams = new ArrayList<>();
            streams.add(firstStream);
            long totalFrames = firstStream.getFrameLength();

            // 构造 500ms 静音数据用于段落停顿
            long silenceFrames = (long) (format.getSampleRate() * 0.5); // 0.5s = 500ms
            byte[] silenceBytes = new byte[(int) (silenceFrames * format.getFrameSize())];

            for (int i = 1; i < segmentFiles.size(); i++) {
                // 插入静音段落
                InputStream silenceInput = new ByteArrayInputStream(silenceBytes);
                AudioInputStream silenceAis = new AudioInputStream(silenceInput, format, silenceFrames);
                streams.add(silenceAis);
                totalFrames += silenceFrames;

                // 插入下一个音频段落
                AudioInputStream ais = AudioSystem.getAudioInputStream(segmentFiles.get(i).toFile());
                totalFrames += ais.getFrameLength();
                streams.add(ais);
            }

            java.util.Enumeration<InputStream> enumeration = java.util.Collections.enumeration(
                    streams.stream().map(s -> (InputStream) s).toList());
            SequenceInputStream seqIn = new SequenceInputStream(enumeration);

            AudioInputStream combined = new AudioInputStream(seqIn, format, totalFrames);
            AudioSystem.write(combined, AudioFileFormat.Type.WAVE, outputFile.toFile());

            for (AudioInputStream ais : streams) {
                ais.close();
            }

            log.info("WAV 音频拼接完成: {} 个发音片段(并插入静音) -> {}", segmentFiles.size(), outputFile);
        } catch (UnsupportedAudioFileException e) {
            throw new RuntimeException("不支持的音频格式", e);
        }
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percent);
    }
}
