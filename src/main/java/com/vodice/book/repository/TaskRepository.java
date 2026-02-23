package com.vodice.book.repository;

import com.vodice.book.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {
    List<Task> findByChapterId(Long chapterId);

    List<Task> findAllByOrderByCreatedAtDesc();
}
