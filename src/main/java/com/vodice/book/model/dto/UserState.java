package com.vodice.book.model.dto;

import lombok.Data;

@Data
public class UserState {
    private Long taskId;
    private Long chapterId;
    private String draftTitle;
    private String draftContent;
}
