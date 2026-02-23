package com.vodice.book.service;

import com.vodice.book.model.Chapter;
import com.vodice.book.repository.ChapterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChapterService {

    private final ChapterRepository chapterRepository;

    public Chapter create(String title, String content) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("章节标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("章节内容不能为空");
        }

        Chapter chapter = new Chapter();
        chapter.setTitle(title.trim());
        chapter.setContent(content.trim());
        return chapterRepository.save(chapter);
    }

    public Chapter getById(Long id) {
        return chapterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + id));
    }

    public List<Chapter> getAll() {
        return chapterRepository.findAll();
    }

    public void delete(Long id) {
        if (!chapterRepository.existsById(id)) {
            throw new IllegalArgumentException("章节不存在: " + id);
        }
        chapterRepository.deleteById(id);
    }
}
