-- Convert audit_ticket_record from one row per ticket/ts to audit detail rows.
-- This migration is safe to run on older databases that are missing async columns
-- and on newer databases where some indexes/columns may already exist.

USE smartdoc;

SET @col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND column_name = 'audit_batch_no'
      AND is_nullable = 'NO'
);
SET @sql := IF(@col > 0,
    'ALTER TABLE audit_ticket_record MODIFY COLUMN audit_batch_no VARCHAR(32) DEFAULT NULL COMMENT ''审核批次号，异步任务完成前为null''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND column_name = 'task_id'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE audit_ticket_record ADD COLUMN task_id VARCHAR(36) DEFAULT NULL COMMENT ''异步任务ID（UUID）''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND column_name = 'status'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE audit_ticket_record ADD COLUMN status VARCHAR(20) DEFAULT NULL COMMENT ''任务状态: PENDING/RUNNING/COMPLETED/FAILED，null=同步审核''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND column_name = 'error_message'
);
SET @sql := IF(@col = 0,
    'ALTER TABLE audit_ticket_record ADD COLUMN error_message VARCHAR(1000) DEFAULT NULL COMMENT ''失败原因''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND index_name = 'idx_ticket_ts'
);
SET @sql := IF(@idx > 0,
    'ALTER TABLE audit_ticket_record DROP INDEX idx_ticket_ts',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND index_name = 'idx_ticket_ts_created_at'
);
SET @sql := IF(@idx = 0,
    'CREATE INDEX idx_ticket_ts_created_at ON audit_ticket_record(ticket_id, ts, created_at)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND index_name = 'uk_task_id'
);
SET @sql := IF(@idx = 0,
    'CREATE UNIQUE INDEX uk_task_id ON audit_ticket_record(task_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'audit_ticket_record'
      AND index_name = 'idx_audit_ticket_batch_no'
);
SET @sql := IF(@idx = 0,
    'CREATE INDEX idx_audit_ticket_batch_no ON audit_ticket_record(audit_batch_no)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
