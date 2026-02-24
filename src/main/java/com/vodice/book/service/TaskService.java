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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository taskRepository;
    private final ChapterService chapterService;
    private final LlmService llmService;
    private final TtsService ttsService;
    private final ObjectMapper objectMapper;

    /**
     * 创建新任务
     */
    public Task createTask(Long chapterId) {
        chapterService.getById(chapterId);

        Task task = new Task();
        task.setChapterId(chapterId);
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        return taskRepository.save(task);
    }

    public Task getById(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    public List<Task> getAll() {
        return taskRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 获取朗读稿
     */
    public ReadingScript getScript(Long taskId) {
        Task task = getById(taskId);
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
    public void updateScript(Long taskId, ReadingScript script) {
        Task task = getById(taskId);
        if (task.getStatus() != TaskStatus.SCRIPT_READY) {
            throw new IllegalStateException("当前状态不允许修改朗读稿: " + task.getStatus());
        }
        try {
            task.setReadingScriptJson(objectMapper.writeValueAsString(script));
            taskRepository.save(task);
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

            Chapter chapter = chapterService.getById(task.getChapterId());

            ReadingScript script = llmService.analyzeChapter(
                    chapter.getId(), chapter.getTitle(), chapter.getContent());

            task.setReadingScriptJson(objectMapper.writeValueAsString(script));
            task.setStatus(TaskStatus.SCRIPT_READY);
            task.setProgress(50);
            taskRepository.save(task);

            log.info("任务 {} LLM 分析完成", taskId);
        } catch (Exception e) {
            log.error("任务 {} LLM 分析失败", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("LLM 分析失败: " + e.getMessage());
            taskRepository.save(task);
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
            // 设置为分析中状态
            task.setStatus(TaskStatus.ANALYZING);
            task.setProgress(10);
            task.setErrorMessage(null);
            taskRepository.save(task);

            // 获取当前朗读稿和原始章节
            ReadingScript currentScript = objectMapper.readValue(
                    task.getReadingScriptJson(), ReadingScript.class);
            Chapter chapter = chapterService.getById(task.getChapterId());

            // 调用 LLM 重新生成
            ReadingScript newScript = llmService.regenerateScript(
                    chapter.getId(), chapter.getTitle(), chapter.getContent(),
                    currentScript, instruction);

            // 保存新的朗读稿
            task.setReadingScriptJson(objectMapper.writeValueAsString(newScript));
            task.setStatus(TaskStatus.SCRIPT_READY);
            task.setProgress(50);
            taskRepository.save(task);

            log.info("任务 {} 朗读稿重新生成完成", taskId);
        } catch (Exception e) {
            log.error("任务 {} 朗读稿重新生成失败", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("AI 重新生成失败: " + e.getMessage());
            taskRepository.save(task);
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

            ReadingScript script = objectMapper.readValue(
                    task.getReadingScriptJson(), ReadingScript.class);

            String audioPath = ttsService.synthesize(taskId, script, percent -> {
                int overallProgress = 50 + (percent / 2);
                task.setProgress(overallProgress);
                taskRepository.save(task);
            });

            task.setAudioFilePath(audioPath);
            task.setStatus(TaskStatus.COMPLETED);
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            taskRepository.save(task);

            log.info("任务 {} TTS 合成完成: {}", taskId, audioPath);
        } catch (Exception e) {
            log.error("任务 {} TTS 合成失败", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage("TTS 合成失败: " + e.getMessage());
            taskRepository.save(task);
        }
    }

    /**
     * 重试失败的任务
     */
    public Task retry(Long taskId) {
        Task task = getById(taskId);
        if (task.getStatus() != TaskStatus.FAILED) {
            throw new IllegalStateException("只有失败的任务才能重试");
        }
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setErrorMessage(null);
        return taskRepository.save(task);
    }
}
