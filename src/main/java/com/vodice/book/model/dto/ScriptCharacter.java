package com.vodice.book.model.dto;

import lombok.Data;

@Data
public class ScriptCharacter {
    private String id;
    private String name;
    private String gender; // "male" / "female" / "unknown"
    private String voice; // 用户手选的 TTS 音色名（如 "Ethan"），可空
    private String voiceProfile;
    private String description;
}
