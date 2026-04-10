-- SmartDoc 数据库初始化脚本
-- 设置字符集
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- 创建数据库
CREATE DATABASE IF NOT EXISTS smartdoc DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE smartdoc;

-- 设置数据库字符集
SET CHARACTER SET utf8mb4;

-- 规则组表
CREATE TABLE IF NOT EXISTS rule_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id VARCHAR(50) NOT NULL UNIQUE,
    group_name VARCHAR(200) NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 规则表
CREATE TABLE IF NOT EXISTS rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_group_id BIGINT NOT NULL,
    rule_name VARCHAR(200) NOT NULL,
    prompt TEXT NOT NULL,
    severity VARCHAR(20) DEFAULT 'WARNING',
    is_enabled BOOLEAN DEFAULT TRUE,
    sort_order INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (rule_group_id) REFERENCES rule_group(id) ON DELETE CASCADE,
    INDEX idx_rule_group_id (rule_group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- API配置表
CREATE TABLE IF NOT EXISTS api_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(50) DEFAULT 'custom',
    endpoint VARCHAR(500),
    api_key VARCHAR(500),
    model VARCHAR(100),
    audit_role VARCHAR(200),
    ticket_endpoint VARCHAR(500),
    ticket_token VARCHAR(500),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 模板表
CREATE TABLE IF NOT EXISTS template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_name VARCHAR(200) NOT NULL,
    file_name VARCHAR(100),
    description VARCHAR(500),
    is_default BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 插入默认API配置
INSERT INTO api_config (id, provider, endpoint, model, audit_role) 
VALUES (1, 'custom', 'https://api.deepseek.com/v1/chat/completions', 'deepseek-chat', '专业文档审核专家')
ON DUPLICATE KEY UPDATE endpoint = VALUES(endpoint);

-- 插入默认规则组
INSERT INTO rule_group (group_id, group_name, is_default) 
VALUES ('znywz', '智能运维组', TRUE);

INSERT INTO rule_group (group_id, group_name, is_default) 
VALUES ('yyxzz', '应用X组', FALSE);

-- 插入示例规则
INSERT INTO rule (rule_group_id, rule_name, prompt, severity, is_enabled, sort_order)
SELECT 1, '文档标题检查', '检查文档是否有明确的标题，标题是否规范', 'WARNING', TRUE, 1;

INSERT INTO rule (rule_group_id, rule_name, prompt, severity, is_enabled, sort_order)
SELECT 1, '文档结构检查', '检查文档是否有清晰的章节结构，是否包含必要的章节', 'WARNING', TRUE, 2;

INSERT INTO rule (rule_group_id, rule_name, prompt, severity, is_enabled, sort_order)
SELECT 1, '关键信息检查', '检查文档是否包含必要的关键信息，如日期、版本号等', 'ERROR', TRUE, 3;