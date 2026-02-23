package com.vodice.book.controller;

import com.vodice.book.model.Chapter;
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
        String title = body.get("title");
        String content = body.get("content");
        Chapter chapter = chapterService.create(title, content);
        return ResponseEntity.ok(chapter);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Chapter> getById(@PathVariable Long id) {
        return ResponseEntity.ok(chapterService.getById(id));
    }

    @GetMapping
    public ResponseEntity<List<Chapter>> getAll() {
        return ResponseEntity.ok(chapterService.getAll());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        chapterService.delete(id);
        return ResponseEntity.ok().build();
    }
}
