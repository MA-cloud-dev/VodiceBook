package com.vodice.book.controller;

import com.vodice.book.model.Chapter;
import com.vodice.book.security.SecurityUtil;
import com.vodice.book.service.ChapterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chapters")
@RequiredArgsConstructor
public class ChapterController {

    private final ChapterService chapterService;

    @PostMapping
    public ResponseEntity<Chapter> create(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtil.getCurrentUserId();
        String title = body.get("title");
        String content = body.get("content");
        Chapter chapter = chapterService.create(userId, title, content);
        return ResponseEntity.ok(chapter);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Chapter> getById(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(chapterService.getById(id, userId));
    }

    @GetMapping
    public ResponseEntity<List<Chapter>> getAll() {
        Long userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(chapterService.getAll(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long userId = SecurityUtil.getCurrentUserId();
        chapterService.delete(id, userId);
        return ResponseEntity.ok().build();
    }
}
