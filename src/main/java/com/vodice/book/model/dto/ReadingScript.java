package com.vodice.book.model.dto;

import lombok.Data;
import java.util.List;

@Data
public class ReadingScript {
    private Long chapterId;
    private String title;
    private List<ScriptCharacter> characters;
    private List<ScriptSegment> segments;
}
