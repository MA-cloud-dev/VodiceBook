package com.vodice.book.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 提供可用 TTS 音色列表，供前端角色编辑时选择。
 * 所有音色均经过 QwenTTS API 真实验证，样本音频存储在 /voice-samples/ 目录下。
 */
@RestController
@RequestMapping("/api/voices")
public class VoiceController {

        // ===== 男声 =====
        private static final List<Map<String, String>> MALE_VOICES = List.of(
                        Map.of("name", "Ethan", "label", "晨煦 Ethan（阳光温暖男青年）", "sample", "/voice-samples/Ethan.wav"),
                        Map.of("name", "Ryan", "label", "甜茶 Ryan（节奏感演技派）", "sample", "/voice-samples/Ryan.wav"),
                        Map.of("name", "Roy", "label", "阿杰 Roy（闽南幽默爽朗）", "sample", "/voice-samples/Roy.wav"),
                        Map.of("name", "Lenn", "label", "Lenn（理性英伦青年）", "sample", "/voice-samples/Lenn.wav"),
                        Map.of("name", "Vincent", "label", "田叔 Vincent（沉稳大叔）", "sample", "/voice-samples/Vincent.wav"),
                        Map.of("name", "Li", "label", "老李 Li（南京温和老者）", "sample", "/voice-samples/Li.wav"),
                        Map.of("name", "Dolce", "label", "Dolce（慵懒意式男声）", "sample", "/voice-samples/Dolce.wav"),
                        Map.of("name", "Elias", "label", "墨讲师 Elias（严谨知识男声）", "sample", "/voice-samples/Elias.wav"),
                        Map.of("name", "Dylan", "label", "晓东 Dylan（北京胡同少年）", "sample", "/voice-samples/Dylan.wav"),
                        Map.of("name", "Marcus", "label", "秦川 Marcus（陕西厚朴男声）", "sample", "/voice-samples/Marcus.wav"),
                        Map.of("name", "Eric", "label", "Eric（成都活力男声）", "sample", "/voice-samples/Eric.wav"),
                        Map.of("name", "Aiden", "label", "Aiden（阳光美式男声）", "sample", "/voice-samples/Aiden.wav"),
                        Map.of("name", "Nofish", "label", "不吃鱼 Nofish（不卷舌设计师）", "sample", "/voice-samples/Nofish.wav"));

        // ===== 女声 =====
        private static final List<Map<String, String>> FEMALE_VOICES = List.of(
                        Map.of("name", "Cherry", "label", "芊悦 Cherry（阳光亲切女青年）", "sample", "/voice-samples/Cherry.wav"),
                        Map.of("name", "Serena", "label", "苏瑶 Serena（温柔小姐姐）", "sample", "/voice-samples/Serena.wav"),
                        Map.of("name", "Vivian", "label", "Vivian（明亮自信女声）", "sample", "/voice-samples/Vivian.wav"),
                        Map.of("name", "Jennifer", "label", "詹妮弗 Jennifer（影院级美式女声）", "sample",
                                        "/voice-samples/Jennifer.wav"),
                        Map.of("name", "Katerina", "label", "卡捷琳娜 Katerina（女王韵律）", "sample",
                                        "/voice-samples/Katerina.wav"),
                        Map.of("name", "Stella", "label", "Stella（高能元气女声）", "sample", "/voice-samples/Stella.wav"),
                        Map.of("name", "Kiki", "label", "Kiki（港式甜妹闺蜜）", "sample", "/voice-samples/Kiki.wav"),
                        Map.of("name", "Nini", "label", "邻家妹妹 Nini（糯叽叽软妹）", "sample", "/voice-samples/Nini.wav"),
                        Map.of("name", "Sohee", "label", "Sohee（温暖韩系女声）", "sample", "/voice-samples/Sohee.wav"),
                        Map.of("name", "Bella", "label", "Bella（可爱童声小女孩）", "sample", "/voice-samples/Bella.wav"),
                        Map.of("name", "Momo", "label", "Momo（元气动漫少女）", "sample", "/voice-samples/Momo.wav"),
                        Map.of("name", "Chelsie", "label", "Chelsie（甜美可爱女声）", "sample", "/voice-samples/Chelsie.wav"),
                        Map.of("name", "Mia", "label", "Mia（活泼明朗女声）", "sample", "/voice-samples/Mia.wav"),
                        Map.of("name", "Ono Anna", "label", "小野杏 Ono Anna（日系灵动女声）", "sample",
                                        "/voice-samples/Ono_Anna.wav"),
                        Map.of("name", "Jada", "label", "阿珍 Jada（上海泼辣大姐）", "sample", "/voice-samples/Jada.wav"),
                        Map.of("name", "Sunny", "label", "晴儿 Sunny（四川乖巧女声）", "sample", "/voice-samples/Sunny.wav"));

        @GetMapping
        public ResponseEntity<Map<String, Object>> getVoices() {
                return ResponseEntity.ok(Map.of(
                                "male", MALE_VOICES,
                                "female", FEMALE_VOICES));
        }
}
