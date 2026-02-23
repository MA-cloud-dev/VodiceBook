package com.vodice.book.model.dto;

import lombok.Data;

@Data
public class ScriptSegment {
    private Integer index;
    private String type; // "narration" or "dialogue"
    private String characterId;
    private String emotion;
    private String text;
}
