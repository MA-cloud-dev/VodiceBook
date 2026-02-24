package com.vodice.book.model.dto;

import lombok.Data;

@Data
public class ScriptSegment {
    private Integer index;
    private String type; // "narration" or "dialogue"
    private String subType; // "general" or "inner_thought" (仅旁白有效)
    private String characterId;
    private String emotion;
    private String text;
}
