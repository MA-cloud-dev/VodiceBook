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

    /**
     * 根据用户修改指令重新生成朗读稿
     */
    public ReadingScript regenerateScript(Long chapterId, String title, String content,
            ReadingScript currentScript, String userInstruction) {
        log.info("根据用户指令重新生成朗读稿，章节 [{}] {}，指令: {}", chapterId, title, userInstruction);

        String prompt = buildRegeneratePrompt(title, content, currentScript, userInstruction);
        String response = callLlm(prompt);

        ReadingScript script = parseResponse(response, chapterId, title);
        log.info("朗读稿重新生成完成：{} 个角色，{} 个段落",
                script.getCharacters().size(), script.getSegments().size());

        return script;
    }

    private String buildPrompt(String title, String content) {
        return """
                你是一位专业的有声书朗读稿编辑，擅长精确区分小说中不同角色的对话与内心活动。

                请分析以下小说文本，严格按照以下规则完成任务：

                【核心规则】

                1. 【对话识别】必须精确识别每句对话的说话人
                   - 引号（""、「」、''）内的内容为对话(dialogue)
                   - 根据上下文判断说话人，如"xxx说"/"xxx道"/"xxx叫道"后的引号内容，characterId为该角色
                   - 对话的 subType 固定为 null

                2. 【旁白分类】旁白(narration)必须区分两类：
                   - subType: "general" — 客观叙述（环境描写、场景切换、动作描写等），characterId 为 "narrator"
                   - subType: "inner_thought" — 角色内心旁白/心理描写，characterId 为该角色ID
                   - 判断依据："xxx心想"/"xxx暗道"/"xxx心中一惊"/"xxx不禁想到" 等归为 inner_thought
                   - 如果无法明确判断是谁的内心活动，则归为 general

                3. 【角色信息】每个角色必须包含：
                   - gender: 性别（"male"/"female"/"unknown"）
                   - voiceProfile: 音色标识（见下方可选值）
                   - description: 一句话描述角色特征

                4. 【完整覆盖】确保原文每个字都被包含在某个 segment 中，不遗漏任何文字

                5. 【分段粒度】
                   - 一段连续的旁白作为一个segment
                   - 一句完整对话作为一个segment（引号内为对话文本，不包含引号和"xxx说"等提示语）
                   - "xxx说"等对话引导语归入旁白segment

                请严格按照以下 JSON 格式输出，不要输出任何其他内容（不要用markdown代码块包裹）：
                {
                  "characters": [
                    {
                      "id": "narrator",
                      "name": "旁白",
                      "gender": "unknown",
                      "voiceProfile": "narrator_default",
                      "description": "叙述者"
                    },
                    {
                      "id": "char_001",
                      "name": "角色名",
                      "gender": "male 或 female",
                      "voiceProfile": "根据性别选择：男用young_male_01/mature_male_01，女用young_female_01/mature_female_01",
                      "description": "角色特征简述"
                    }
                  ],
                  "segments": [
                    {
                      "index": 0,
                      "type": "narration",
                      "subType": "general",
                      "characterId": "narrator",
                      "emotion": "neutral",
                      "text": "旁白文本"
                    },
                    {
                      "index": 1,
                      "type": "dialogue",
                      "subType": null,
                      "characterId": "char_001",
                      "emotion": "curious",
                      "text": "对话文本内容"
                    },
                    {
                      "index": 2,
                      "type": "narration",
                      "subType": "inner_thought",
                      "characterId": "char_001",
                      "emotion": "serious",
                      "text": "角色内心独白文本"
                    }
                  ]
                }

                voiceProfile 可选值：
                - narrator_default：旁白默认音色
                - young_male_01：青年男性
                - young_female_01：青年女性
                - mature_male_01：成熟男性
                - mature_female_01：成熟女性
                - child_01：儿童

                emotion 可选值：neutral, happy, angry, sad, fearful, surprised, gentle, serious, curious, shy

                以下是需要分析的小说章节：

                标题：""" + title + """

                正文：
                """ + content;
    }

    private String buildRegeneratePrompt(String title, String content,
            ReadingScript currentScript, String userInstruction) {
        String currentScriptJson;
        try {
            currentScriptJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(currentScript);
        } catch (JsonProcessingException e) {
            currentScriptJson = "（无法序列化当前朗读稿）";
        }

        return """
                你是一位专业的有声书朗读稿编辑。你之前已经为以下小说生成了一版朗读稿，现在用户希望进行修改。

                【当前朗读稿】
                """ + currentScriptJson + """

                【用户修改指令】
                """ + userInstruction + """

                请根据用户的修改指令，重新生成完整的朗读稿。保持与原版相同的 JSON 格式。
                注意：
                1. 只修改用户要求修改的部分，其余部分保持不变
                2. 确保修改后原文内容仍然被完整覆盖
                3. 输出严格 JSON 格式，不要用markdown代码块包裹

                原文标题：""" + title + """

                原文正文：
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
        messages.add(Map.of("role", "system", "content",
                "你是一个专业的有声书朗读稿编辑，擅长精确识别小说中的角色对话和内心活动。只输出JSON格式的结果，不要输出任何其他内容。"));
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
            String responseContent = root.path("choices").path(0).path("message").path("content").asText();
            log.info("LLM 返回内容长度: {} 字符", responseContent.length());
            return responseContent;
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
