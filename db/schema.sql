-- ======================================================================
-- quanta 数据库脚本（P0）
-- 目标数据库：MySQL 8（utf8mb4 / InnoDB）
-- 执行方式：mysql -uroot -p < db/schema.sql
-- 说明：M1（配置层）仅建 sys_config 一张表；
--       其余 17 张表（设计 §9）在 M2（持久层）追加到本文件。
-- ======================================================================

CREATE DATABASE IF NOT EXISTS quanta
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE quanta;

-- ----------------------------------------------------------------------
-- 1) 系统配置（凭据密文存储；大模型配置内含 token_budget_daily，空=无上限）
--    config_type: MT5 / LLM / MAIL
--    config_json: AES 密文（整体加密，明文不入库）
--    name 可多套；同类型下 name 唯一
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  config_type  VARCHAR(16)  NOT NULL COMMENT 'MT5/LLM/MAIL',
  name         VARCHAR(64)  NOT NULL COMMENT '连接名，可多套',
  config_json  TEXT         NOT NULL COMMENT 'AES 密文',
  is_active    TINYINT      NOT NULL DEFAULT 0,
  test_status  VARCHAR(16)  DEFAULT NULL,
  tested_at    DATETIME     DEFAULT NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_type_name (config_type, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置';