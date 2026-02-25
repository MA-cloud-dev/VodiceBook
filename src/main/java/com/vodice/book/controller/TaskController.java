package com.vodice.book.controller;

import com.vodice.book.model.Task;
import com.vodice.book.model.dto.ReadingScript;
import com.vodice.book.security.JwtTokenProvider;
import com.vodice.book.security.SecurityUtil;
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
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 创建任务并立即开始 LLM 分析
     */
    @PostMapping
    public ResponseEntity<Task> create(@RequestBody Map<String, Long> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        Long chapterId = body.get("chapterId");
        if (chapterId == null) {
            return ResponseEntity.badRequest().build();
        }
        Task task = taskService.createTask(userId, chapterId);

        // 自动开始 LLM 分析
        taskService.startAnalysis(task.getId());

        return ResponseEntity.ok(task);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Task> getById(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(taskService.getById(id, userId));
    }

    @GetMapping
    public ResponseEntity<List<Task>> getAll() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(taskService.getAll(userId));
    }

    /**
     * 获取朗读稿
     */
    @GetMapping("/{id}/script")
    public ResponseEntity<ReadingScript> getScript(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(taskService.getScript(id, userId));
    }

    /**
     * 更新朗读稿（用户手动修改）
     */
    @PutMapping("/{id}/script")
    public ResponseEntity<Void> updateScript(@PathVariable Long id,
            @RequestBody ReadingScript script) {
        Long userId = SecurityUtil.getCurrentUserId();
        taskService.updateScript(id, userId, script);
        return ResponseEntity.ok().build();
    }

    /**
     * AI 重新生成朗读稿（根据用户指令）
     */
    @PostMapping("/{id}/script/regenerate")
    public ResponseEntity<Task> regenerateScript(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        String instruction = body.get("instruction");
        if (instruction == null || instruction.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        taskService.regenerateScript(id, instruction);
        return ResponseEntity.ok(taskService.getById(id, userId));
    }

    /**
     * 开始 TTS 合成
     */
    @PostMapping("/{id}/synthesize")
    public ResponseEntity<Task> synthesize(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        // 校验权限
        taskService.getById(id, userId);
        taskService.startSynthesis(id);
        return ResponseEntity.ok(taskService.getById(id, userId));
    }

    /**
     * 播放/下载音频（支持浏览器在线播放）
     */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> downloadAudio(@PathVariable Long id,
            @RequestParam(value = "token", required = false) String token) {
        // 音频端点通过 query param token 认证（HTML audio 标签不支持 header）
        Long userId;
        if (token != null && jwtTokenProvider.validateToken(token)) {
            userId = jwtTokenProvider.getUserIdFromToken(token);
        } else {
            try {
                userId = SecurityUtil.getCurrentUserId();
            } catch (Exception e) {
                return ResponseEntity.status(401).build();
            }
        }
        Task task = taskService.getById(id, userId);
        if (task.getAudioFilePath() == null) {
            return ResponseEntity.notFound().build();
        }

        File audioFile = new File(task.getAudioFilePath());
        if (!audioFile.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(audioFile);
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
        Long userId = SecurityUtil.getCurrentUserId();
        Task task = taskService.retry(id, userId);
        taskService.startAnalysis(task.getId());
        return ResponseEntity.ok(task);
    }

    /**
     * 删除单个任务
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        taskService.deleteTask(id, userId);
        return ResponseEntity.ok().build();
    }

    /**
     * 批量删除任务
     */
    @DeleteMapping("/batch")
    public ResponseEntity<Void> deleteBatch(@RequestBody Map<String, List<Long>> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        List<Long> ids = body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        taskService.deleteTaskBatch(ids, userId);
        return ResponseEntity.ok().build();
    }
}
