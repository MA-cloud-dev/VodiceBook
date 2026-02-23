package com.vodice.book.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodice.book.model.dto.ReadingScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
public class LlmService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${llm.api-url}")
    private String apiUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.max-tokens}")
    private int maxTokens;

    @Value("${llm.temperature}")
    private double temperature;

    @Value("${llm.timeout-seconds}")
    private int timeoutSeconds;

    public LlmService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 分析章节文本，生成结构化朗读稿
     */
    public ReadingScript analyzeChapter(Long chapterId, String title, String content) {
        log.info("开始分析章节 [{}] {}，文本长度: {} 字", chapterId, title, content.length());

        String prompt = buildPrompt(title, content);

        String response = callLlm(prompt);

        ReadingScript script = parseResponse(response, chapterId, title);
        log.info("朗读稿生成完成：{} 个角色，{} 个段落",
                script.getCharacters().size(), script.getSegments().size());

        return script;
    }

    private String buildPrompt(String title, String content) {
        return """
                你是一位专业的有声书朗读稿编辑。请分析以下小说文本，完成以下任务：

                1. 识别所有出现的角色（包括旁白/叙述者）
                2. 将文本拆分为连续的段落，每个段落标注：
                   - 类型：旁白(narration) 或 对话(dialogue)
                   - 说话角色
                   - 情感状态（可选值：neutral, happy, angry, sad, fearful, surprised, gentle, serious, curious, shy）
                3. 确保所有原文内容都被覆盖，不遗漏任何文字

                请严格按照以下 JSON 格式输出，不要输出任何其他内容（不要用markdown代码块包裹）：
                {
                  "characters": [
                    {
                      "id": "narrator",
                      "name": "旁白",
                      "voiceProfile": "narrator_default",
                      "description": "叙述者"
                    },
                    {
                      "id": "char_001",
                      "name": "角色名",
                      "voiceProfile": "young_male_01 或 young_female_01",
                      "description": "角色特征简述"
                    }
                  ],
                  "segments": [
                    {
                      "index": 0,
                      "type": "narration 或 dialogue",
                      "characterId": "narrator 或 char_xxx",
                      "emotion": "情感标注",
                      "text": "原文文本内容"
                    }
                  ]
                }

                voiceProfile 可选值说明：
                - narrator_default：旁白默认音色
                - young_male_01：青年男性音色
                - young_female_01：青年女性音色
                - mature_male_01：成熟男性音色
                - mature_female_01：成熟女性音色
                - child_01：儿童音色

                以下是需要分析的小说章节：

                标题：""" + title + """

                正文：
                """ + content;
    }

    private String callLlm(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("temperature", temperature);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "你是一个专业的有声书朗读稿编辑，只输出JSON格式的结果。"));
        messages.add(Map.of("role", "user", "content", prompt));
        requestBody.put("messages", messages);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("发送 LLM 请求到 {}，模型: {}", apiUrl, model);

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl, HttpMethod.POST, entity, String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("LLM API 调用失败，状态码: " + response.getStatusCode());
        }

        // 从 OpenAI 兼容格式中提取 content
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            log.info("LLM 返回内容长度: {} 字符", content.length());
            return content;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析 LLM 响应失败", e);
        }
    }

    private ReadingScript parseResponse(String responseContent, Long chapterId, String title) {
        try {
            // 清理可能的 markdown 代码块标记
            String json = responseContent.trim();
            if (json.startsWith("```json")) {
                json = json.substring(7);
            } else if (json.startsWith("```")) {
                json = json.substring(3);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3);
            }
            json = json.trim();

            ReadingScript script = objectMapper.readValue(json, ReadingScript.class);
            script.setChapterId(chapterId);
            script.setTitle(title);
            return script;
        } catch (JsonProcessingException e) {
            log.error("解析朗读稿 JSON 失败: {}", responseContent, e);
            throw new RuntimeException("朗读稿 JSON 解析失败，请重试", e);
        }
    }
}
