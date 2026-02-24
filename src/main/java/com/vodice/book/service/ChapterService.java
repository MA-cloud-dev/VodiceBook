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

    public Chapter create(Long userId, String title, String content) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("章节标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("章节内容不能为空");
        }

        Chapter chapter = new Chapter();
        chapter.setUserId(userId);
        chapter.setTitle(title.trim());
        chapter.setContent(content.trim());
        return chapterRepository.save(chapter);
    }

    public Chapter getById(Long id, Long userId) {
        return chapterRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + id));
    }

    /**
     * 内部调用（不校验用户，用于异步任务场景）
     */
    public Chapter getById(Long id) {
        return chapterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + id));
    }

    public List<Chapter> getAll(Long userId) {
        return chapterRepository.findByUserId(userId);
    }

    public void delete(Long id, Long userId) {
        Chapter chapter = chapterRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + id));
        chapterRepository.delete(chapter);
    }
}
