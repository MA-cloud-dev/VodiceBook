# VodiceBook MVP 实现文档

> MVP 阶段已完成 — 2026.02.23

## 1. 项目概述

VodiceBook 是一个 **AI 驱动的小说转有声书系统**。用户上传小说章节文本后，系统自动完成角色识别、朗读稿生成、多角色语音合成，输出可在线播放和下载的音频文件。

### 核心流程

```
上传章节文本 → LLM 角色识别 → 生成朗读稿 → QwenTTS 多角色合成 → 音频拼接 → 在线播放/下载
```

---

## 2. 技术架构

### 2.1 技术栈

| 组件 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3.2.5 | Java 21 |
| 构建工具 | Maven | pom.xml 管理依赖 |
| 数据库 | H2 (文件模式) | `./data/vodicebook.mv.db` |
| ORM | Spring Data JPA + Hibernate | 自动建表 |
| 模板引擎 | Thymeleaf | 服务端渲染 |
| 前端 | 原生 HTML + CSS + JS | 无框架依赖 |
| LLM 服务 | SiliconFlow API | Qwen2.5-7B-Instruct |
| TTS 服务 | 阿里云 DashScope | qwen3-tts-flash |
| 音频处理 | javax.sound.sampled | 纯 Java WAV 合并 |
| 配置管理 | `.env` + `application.yml` | 环境变量注入 |

### 2.2 分层架构

```
┌─────────────────────────────────────────────────────┐
│                    展示层 (Web UI)                    │
│   Thymeleaf (index.html) + CSS + JavaScript         │
├─────────────────────────────────────────────────────┤
│                   接口层 (Controller)                 │
│   ChapterController · TaskController · PageController│
├─────────────────────────────────────────────────────┤
│                    业务层 (Service)                   │
│   ChapterService · TaskService · LlmService · TtsService │
├─────────────────────────────────────────────────────┤
│                   持久层 (Repository)                 │
│   ChapterRepository · TaskRepository (Spring Data JPA)│
├─────────────────────────────────────────────────────┤
│                   数据层 (H2 Database)                │
│          chapters 表 · tasks 表 · 文件存储             │
└─────────────────────────────────────────────────────┘
```

---

## 3. 源代码结构

```
src/main/java/com/vodice/book/
├── VodiceBookApplication.java          # 启动类
├── config/
│   └── AppConfig.java                  # RestTemplate、线程池配置
├── controller/
│   ├── ChapterController.java          # 章节 CRUD API
│   ├── TaskController.java             # 任务管理 API + 音频播放
│   └── PageController.java             # 页面路由
├── exception/
│   └── GlobalExceptionHandler.java     # 统一异常处理
├── model/
│   ├── Chapter.java                    # 章节实体
│   ├── Task.java                       # 任务实体
│   ├── TaskStatus.java                 # 任务状态枚举
│   └── dto/
│       ├── ReadingScript.java          # 朗读稿 DTO
│       ├── ScriptCharacter.java        # 角色 DTO
│       └── ScriptSegment.java          # 段落 DTO
├── repository/
│   ├── ChapterRepository.java          # 章节数据访问
│   └── TaskRepository.java             # 任务数据访问
└── service/
    ├── ChapterService.java             # 章节业务逻辑
    ├── TaskService.java                # 任务编排（异步）
    ├── LlmService.java                 # LLM 角色分析
    └── TtsService.java                 # TTS 语音合成 + WAV 合并

src/main/resources/
├── application.yml                     # 应用配置
├── templates/
│   └── index.html                      # 单页应用模板
└── static/
    ├── css/style.css                   # 全局样式
    └── js/app.js                       # 前端交互逻辑
```

---

## 4. 核心模块逻辑

### 4.1 LLM 朗读稿生成 (`LlmService`)

**调用链路**：`SiliconFlow API → Qwen2.5-7B-Instruct`

**处理流程**：
1. 接收章节文本，构建 Prompt 要求 LLM 识别角色和情感
2. 通过 OpenAI 兼容格式调用 SiliconFlow API
3. 解析返回的 JSON 结构化朗读稿
4. 朗读稿包含：角色列表 + 分段列表（每段标注角色ID、类型、情感）

**Prompt 核心指令**：
- 识别所有角色（包括旁白）
- 将文本拆分为连续段落
- 每段标注类型（narration/dialogue）、角色、情感
- 输出严格 JSON 格式

### 4.2 TTS 语音合成 (`TtsService`)

**调用链路**：`DashScope API → qwen3-tts-flash`

**处理流程**：
1. 读取朗读稿的段落列表
2. 根据角色的 `voiceProfile` 映射到 QwenTTS 音色
3. 逐段调用 DashScope 原生 API（非 OpenAI 兼容格式）
4. 从返回的 JSON 中提取 OSS 音频 URL
5. 使用 `java.net.URI.toURL().openStream()` 下载 WAV
6. 使用 `javax.sound.sampled.AudioInputStream` 合并多段 WAV
7. 输出完整的 `.wav` 文件

**音色映射表**（MVP 阶段）：

| voiceProfile | QwenTTS 音色 | 说明 |
|-------------|-------------|------|
| narrator_default | Ethan | 叙述者 |
| young_male_01 | Ethan | 青年男性 |
| young_female_01 | Cherry | 青年女性 |
| mature_male_01 | Ethan | 成熟男性 |
| mature_female_01 | Serena | 成熟女性 |
| child_01 | Chelsie | 儿童 |

**DashScope API 请求格式**：
```json
{
  "model": "qwen3-tts-flash",
  "input": {
    "text": "段落文本",
    "voice": "Cherry",
    "language_type": "Chinese"
  }
}
```

### 4.3 任务管理 (`TaskService`)

**状态流转**：
```
PENDING → ANALYZING → SCRIPT_READY → SYNTHESIZING → COMPLETED
                ↓                          ↓
              FAILED                     FAILED → PENDING (重试)
```

**异步处理**：使用 Spring `@Async` + 自定义线程池（`VodiceTask-`前缀，核心2线程，最大4线程）

### 4.4 前端交互 (`app.js`)

**轮询机制**：前端每 2 秒轮询 `/api/tasks/{id}` 获取最新状态
- `ANALYZING` → 显示进度条 + 状态文字
- `SCRIPT_READY` → 停止轮询，渲染朗读稿预览
- `SYNTHESIZING` → 继续轮询，显示合成进度
- `COMPLETED` → 停止轮询，显示音频播放器
- `FAILED` → 停止轮询，显示错误信息

**音频播放**：`<audio>` 标签直接请求 `/api/tasks/{id}/audio`，Content-Type 为 `audio/wav`，Content-Disposition 为 `inline`（支持浏览器内播放）

---

## 5. API 接口清单

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | `/api/chapters` | 创建章节 | ✅ |
| GET | `/api/chapters/{id}` | 获取章节 | ✅ |
| GET | `/api/chapters` | 章节列表 | ✅ |
| POST | `/api/tasks` | 创建任务（自动开始分析） | ✅ |
| GET | `/api/tasks/{id}` | 查询任务状态 | ✅ |
| GET | `/api/tasks` | 任务列表 | ✅ |
| GET | `/api/tasks/{id}/script` | 获取朗读稿 | ✅ |
| PUT | `/api/tasks/{id}/script` | 更新朗读稿 | ✅ |
| POST | `/api/tasks/{id}/synthesize` | 开始 TTS 合成 | ✅ |
| GET | `/api/tasks/{id}/audio` | 播放/下载音频 | ✅ |
| POST | `/api/tasks/{id}/retry` | 重试失败任务 | ✅ |

---

## 6. 配置说明

### 环境变量 (`.env`)

```env
LLM_API_KEY=<SiliconFlow API Key>
LLM_MODEL=Qwen/Qwen2.5-7B-Instruct
TTS_API_KEY=<DashScope API Key>
TTS_MODEL=qwen3-tts-flash
TTS_API_URL=https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
```

### 文件存储

```
storage/
├── audio/
│   ├── segments/{taskId}/    # 临时 WAV 片段
│   │   ├── 000.wav
│   │   ├── 001.wav
│   │   └── ...
│   └── output/               # 最终音频
│       └── {taskId}.wav
```

---

## 7. MVP 阶段已验证的功能

- [x] 章节文本上传和存储
- [x] LLM 自动角色识别（旁白 + 多角色）
- [x] 情感标注（neutral/assertive/happy 等）
- [x] 结构化朗读稿生成和预览
- [x] QwenTTS 多角色语音合成
- [x] WAV 音频正确合并（javax.sound）
- [x] 浏览器在线播放音频
- [x] 音频下载
- [x] 任务状态实时轮询
- [x] 任务历史记录
- [x] 失败任务重试

## 8. 已知限制与后续方向

| 限制 | 后续方向 |
|------|---------|
| H2 内存/文件数据库 | 迁移至 MySQL |
| 单章节处理 | 支持多章节批量处理 |
| 固定音色映射 | 音色库管理 + 用户自定义 |
| 无段落停顿 | 合成时添加段间静音 |
| 无用户认证 | 用户系统 + 权限管理 |
| WAV 格式输出 | 转码为 MP3 减小体积 |
| 前端简单 | 升级为 Vue/React SPA |
