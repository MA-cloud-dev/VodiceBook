package com.vodice.book.repository;

import com.vodice.book.model.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterRepository extends JpaRepository<Chapter, Long> {
    List<Chapter> findByUserId(Long userId);

    Optional<Chapter> findByIdAndUserId(Long id, Long userId);
}
