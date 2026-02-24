package com.vodice.book.controller;

import com.vodice.book.model.Task;
import com.vodice.book.model.dto.ReadingScript;
import com.vodice.book.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /**
     * 创建任务并立即开始 LLM 分析
     */
    @PostMapping
    public ResponseEntity<Task> create(@RequestBody Map<String, Long> body) {
        Long chapterId = body.get("chapterId");
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }
        Task task = taskService.createTask(chapterId);

        // 自动开始 LLM 分析
        taskService.startAnalysis(task.getId());

        return ResponseEntity.ok(task);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> getById(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<Task>> getAll() {
        return ResponseEntity.ok(taskService.getAll());
    }

    /**
     * 获取朗读稿
     */
    @GetMapping("/{id}/script")
    public ResponseEntity<ReadingScript> getScript(@PathVariable Long id) {
        return ResponseEntity.ok(taskService.getScript(id));
    }

    /**
     * 更新朗读稿（用户手动修改）
     */
    @PutMapping("/{id}/script")
    public ResponseEntity<Void> updateScript(@PathVariable Long id,
            @RequestBody ReadingScript script) {
        taskService.updateScript(id, script);
        return ResponseEntity.ok().build();
    }

    /**
     * AI 重新生成朗读稿（根据用户指令）
     */
    @PostMapping("/{id}/script/regenerate")
    public ResponseEntity<Task> regenerateScript(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String instruction = body.get("instruction");
        if (instruction == null || instruction.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        taskService.regenerateScript(id, instruction);
        return ResponseEntity.ok(taskService.getById(id));
    }

    /**
     * 开始 TTS 合成
     */
    @PostMapping("/{id}/synthesize")
    public ResponseEntity<Task> synthesize(@PathVariable Long id) {
        taskService.startSynthesis(id);
        return ResponseEntity.ok(taskService.getById(id));
    }

    /**
     * 播放/下载音频（支持浏览器在线播放）
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> downloadAudio(@PathVariable Long id) {
        Task task = taskService.getById(id);
        if (task.getAudioFilePath() == null) {
            return ResponseEntity.notFound().build();
        }

        File audioFile = new File(task.getAudioFilePath());
        if (!audioFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(audioFile);
        // 使用 audio/mpeg 类型 + inline 模式，支持浏览器在线播放
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + audioFile.getName() + "\"")
                .body(resource);
    }

    /**
     * 重试失败任务
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Task> retry(@PathVariable Long id) {
        Task task = taskService.retry(id);
        taskService.startAnalysis(task.getId());
        return ResponseEntity.ok(task);
    }
}
