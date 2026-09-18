-- SmartDoc 规则训练提示词自定义 升级脚本（针对已初始化的存量数据库执行）
-- 执行内容：
--   1. 新建 prompt_override 表：按提示词模板名（prompt_key）存放用户自定义的覆盖内容
-- 存量数据不受影响：表中无记录时，规则训练仍使用 src/main/resources/prompts/ 下的内置模板，行为与升级前一致。

USE smartdoc;

CREATE TABLE IF NOT EXISTS prompt_override (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    prompt_key VARCHAR(64) NOT NULL UNIQUE COMMENT '提示词模板名（对应 prompts/{key}.prompt）',
    content TEXT NOT NULL COMMENT '用户自定义的提示词内容（含 {id} 形式占位符）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_prompt_key (prompt_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='提示词自定义覆盖表';
