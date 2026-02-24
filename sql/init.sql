-- ================================================
-- VodiceBook 数据库初始化脚本
-- 执行方式：在 MySQL 客户端中手动执行
-- ================================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS vodicebook CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE vodicebook;

-- 2. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '登录用户名',
    password VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密密码',
    nickname VARCHAR(64) NULL COMMENT '昵称',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

-- 3. 章节表
CREATE TABLE IF NOT EXISTS chapters (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    title VARCHAR(128) NOT NULL COMMENT '章节标题',
    content LONGTEXT NOT NULL COMMENT '章节正文',
    word_count INT NULL COMMENT '字数统计',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '章节表';

-- 4. 任务表
CREATE TABLE IF NOT EXISTS tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '所属用户',
    chapter_id BIGINT NOT NULL COMMENT '关联章节',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',
    progress INT NOT NULL DEFAULT 0 COMMENT '进度百分比',
    reading_script_json LONGTEXT NULL COMMENT '朗读稿JSON',
    audio_file_path VARCHAR(512) NULL COMMENT '音频文件路径',
    error_message TEXT NULL COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_chapter_id (chapter_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '任务表';

-- 5. 插入测试账号 (密码: 123456, BCrypt 加密)
-- BCrypt hash for '123456': $2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6CQARPepBkCnh2G/0nSv.rkGK
INSERT INTO
    users (username, password, nickname)
VALUES (
        'admin',
        '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6CQARPepBkCnh2G/0nSv.rkGK',
        '管理员'
    )
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname);