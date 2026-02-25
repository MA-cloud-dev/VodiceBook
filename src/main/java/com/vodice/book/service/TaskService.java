package com.vodice.book.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vodice.book.model.Chapter;
import com.vodice.book.model.Task;
import com.vodice.book.model.TaskStatus;
import com.vodice.book.model.dto.ReadingScript;
import com.vodice.book.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ChapterService chapterService;
    private final LlmService llmService;
    private final TtsService ttsService;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String TASK_STATUS_KEY = "task:status:";

    /**
     * 创建新任务
     */
    public Task createTask(Long userId, Long chapterId) {
        chapterService.getById(chapterId, userId);

        Task task = new Task();
        task.setUserId(userId);
        task.setChapterId(chapterId);
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        Task saved = taskRepository.save(task);
        cacheTaskStatus(saved);
        return saved;
    }

    public Task getById(Long id, Long userId) {
        // 先尝试从 Redis 读取状态
        Task cached = getCachedTaskStatus(id);
        if (cached != null && cached.getUserId().equals(userId)) {
            return cached;
        }
        return taskRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    /**
     * 内部调用（异步任务用，不校验用户）
     */
    public Task getById(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    public List<Task> getAll(Long userId) {
        return taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 获取朗读稿
     */
    public ReadingScript getScript(Long taskId, Long userId) {
        Task task = getById(taskId, userId);
        if (task.getReadingScriptJson() == null) {
            throw new IllegalStateException("朗读稿尚未生成");
        }
        try {
            return objectMapper.readValue(task.getReadingScriptJson(), ReadingScript.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("解析朗读稿失败", e);
        }
    }

    /**
     * 更新朗读稿（用户手动修改后提交）
     */
    public void updateScript(Long taskId, Long userId, ReadingScript script) {
        Task task = getById(taskId, userId);
        if (task.getStatus() != TaskStatus.SCRIPT_READY) {
            throw new IllegalStateException("当前状态不允许修改朗读稿: " + task.getStatus());
        }
        try {
            task.setReadingScriptJson(objectMapper.writeValueAsString(script));
            taskRepository.save(task);
            cacheTaskStatus(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化朗读稿失败", e);
        }
    }

    /**
     * 异步执行 LLM 分析
     */
    @Async("taskExecutor")
    public void startAnalysis(Long taskId) {
        Task task = getById(taskId);

        try {
            task.setStatus(TaskStatus.ANALYZING);
            task.setProgress(10);
            taskRepository.save(task);
            cacheTaskStatus(task);

            Chapter chapter = chapterService.getById(task.getChapterId());

            ReadingScript script = llmService.analyzeChapter(
                    chapter.getId(), chapter.getTitle(), chapter.getContent());

            task.setReadingScriptJson(objectMapper.writeValueAsString(script));
            task.setStatus(TaskStatus.SCRIPT_READY);
            task.setProgress(50);
            taskRepository.save(task);
            cacheTaskStatus(task);

            log.info("任务 {} LLM 分析完成", taskId);
        } catch (Exception e) {
            log.error("任务 {} LLM 分析失败", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("LLM 分析失败: " + e.getMessage());
            taskRepository.save(task);
            cacheTaskStatus(task);
        }
    }

    /**
     * 异步 AI 重新生成朗读稿（根据用户指令修改）
     */
    @Async("taskExecutor")
    public void regenerateScript(Long taskId, String instruction) {
        Task task = getById(taskId);

        if (task.getStatus() != TaskStatus.SCRIPT_READY) {
            throw new IllegalStateException("朗读稿未就绪，无法重新生成: " + task.getStatus());
        }

        try {
            task.setStatus(TaskStatus.ANALYZING);
            task.setProgress(10);
            task.setErrorMessage(null);
            taskRepository.save(task);
            cacheTaskStatus(task);

            ReadingScript currentScript = objectMapper.readValue(
                    task.getReadingScriptJson(), ReadingScript.class);
            Chapter chapter = chapterService.getById(task.getChapterId());

            ReadingScript newScript = llmService.regenerateScript(
                    chapter.getId(), chapter.getTitle(), chapter.getContent(),
                    currentScript, instruction);

            task.setReadingScriptJson(objectMapper.writeValueAsString(newScript));
            task.setStatus(TaskStatus.SCRIPT_READY);
            task.setProgress(50);
            taskRepository.save(task);
            cacheTaskStatus(task);

            log.info("任务 {} 朗读稿重新生成完成", taskId);
        } catch (Exception e) {
            log.error("任务 {} 朗读稿重新生成失败", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("AI 重新生成失败: " + e.getMessage());
            taskRepository.save(task);
            cacheTaskStatus(task);
        }
    }

    /**
     * 异步执行 TTS 合成
     */
    @Async("taskExecutor")
    public void startSynthesis(Long taskId) {
        Task task = getById(taskId);

        if (task.getStatus() != TaskStatus.SCRIPT_READY) {
            throw new IllegalStateException("朗读稿未就绪，无法开始合成");
        }

        try {
            task.setStatus(TaskStatus.SYNTHESIZING);
            task.setProgress(50);
            taskRepository.save(task);
            cacheTaskStatus(task);

            ReadingScript script = objectMapper.readValue(
                    task.getReadingScriptJson(), ReadingScript.class);

            String audioPath = ttsService.synthesize(task.getUserId(), taskId, script, percent -> {
                int overallProgress = 50 + (percent / 2);
                task.setProgress(overallProgress);
                taskRepository.save(task);
                cacheTaskStatus(task);
            });

            task.setAudioFilePath(audioPath);
            task.setStatus(TaskStatus.COMPLETED);
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);
            cacheTaskStatus(task);

            log.info("任务 {} TTS 合成完成: {}", taskId, audioPath);
        } catch (Exception e) {
            log.error("任务 {} TTS 合成失败", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("TTS 合成失败: " + e.getMessage());
            taskRepository.save(task);
            cacheTaskStatus(task);
        }
    }

    /**
     * 重试失败的任务
     */
    public Task retry(Long taskId, Long userId) {
        Task task = getById(taskId, userId);
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new IllegalStateException("只有失败的任务才能重试");
        }
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setErrorMessage(null);
        Task saved = taskRepository.save(task);
        cacheTaskStatus(saved);
        return saved;
    }

    /**
     * 删除单个任务
     */
    public void deleteTask(Long taskId, Long userId) {
        Task task = getById(taskId, userId);
        cleanupTask(task);
        taskRepository.delete(task);
    }

    /**
     * 批量删除任务
     */
    public void deleteTaskBatch(List<Long> taskIds, Long userId) {
        for (Long id : taskIds) {
            try {
                deleteTask(id, userId);
            } catch (Exception e) {
                log.warn("删除任务 {} 失败: {}", id, e.getMessage());
            }
        }
    }

    /**
     * 清理任务关联资源（音频文件、Redis缓存）
     */
    private void cleanupTask(Task task) {
        // 清理音频文件
        if (task.getAudioFilePath() != null) {
            java.io.File audioFile = new java.io.File(task.getAudioFilePath());
            if (audioFile.exists()) {
                audioFile.delete();
            }
        }
        // 清理 Redis 缓存
        try {
            redisTemplate.delete(TASK_STATUS_KEY + task.getId());
        } catch (Exception e) {
            log.warn("清理 Redis 缓存失败: {}", e.getMessage());
        }
    }

    // ===== Redis 缓存辅助 =====

    private void cacheTaskStatus(Task task) {
        try {
            Map<String, Object> statusMap = new HashMap<>();
            statusMap.put("id", task.getId());
            statusMap.put("userId", task.getUserId());
            statusMap.put("chapterId", task.getChapterId());
            statusMap.put("status", task.getStatus().name());
            statusMap.put("progress", task.getProgress());
            statusMap.put("audioFilePath", task.getAudioFilePath());
            statusMap.put("errorMessage", task.getErrorMessage());
            redisTemplate.opsForValue().set(
                    TASK_STATUS_KEY + task.getId(), statusMap, 5, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis 缓存任务状态失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Task getCachedTaskStatus(Long taskId) {
        try {
            Object cached = redisTemplate.opsForValue().get(TASK_STATUS_KEY + taskId);
            if (cached instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) cached;
                // 对于轮询场景，直接返回数据库的完整对象更可靠
                // Redis 只作为快速判断层
                return null; // 简化实现：始终回落到数据库
            }
        } catch (Exception e) {
            log.warn("Redis 读取缓存失败: {}", e.getMessage());
        }
        return null;
    }
}
