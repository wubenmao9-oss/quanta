-- ======================================================================
-- 迁移：笨猫马丁-网格双边（P1）回测新增列（幂等，可重复执行）
-- 目标库：quanta（MySQL 8.4.0 @127.0.0.1:3307）
-- 目的：为多腿/网格（BASKET）策略回测补充「轮级口径」与明细标注列。
-- 说明：
--   1) 新增列全部可空，历史数据无需回填，旧代码/旧数据不受影响；
--   2) 通过 information_schema 判断列是否存在，重复执行安全（幂等）；
--   3) 建表 DDL（db/schema.sql）已同步包含这些列，全新安装无需再跑本脚本。
-- 执行方式（本机 MySQL 8.4 客户端）：
--   E:\mysql8\mysql-8.4.0-winx64\bin\mysql.exe --host=127.0.0.1 --port=3307 -uroot -p < db\migration_martin_grid.sql
-- ======================================================================

USE quanta;

DROP PROCEDURE IF EXISTS add_col_if_missing;

DELIMITER $$
CREATE PROCEDURE add_col_if_missing(IN p_table VARCHAR(64),
                                    IN p_col VARCHAR(64),
                                    IN p_ddl VARCHAR(255))
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = p_table
                     AND COLUMN_NAME = p_col) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @s;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 回测明细：平仓原因 / 轮号 / 腿序号 / 引擎类型（多腿策略逐腿落库）
CALL add_col_if_missing('backtest_trade', 'exit_reason',
     '`exit_reason` VARCHAR(16) DEFAULT NULL COMMENT ''平仓原因 TP/SL/HARD_SL/END/DAY_LOCK/TOTAL_LOCK/MAX_LEVEL''');
CALL add_col_if_missing('backtest_trade', 'cycle_no',
     '`cycle_no` INT DEFAULT NULL COMMENT ''所属轮编号(单仓=0)''');
CALL add_col_if_missing('backtest_trade', 'level',
     '`level` INT DEFAULT NULL COMMENT ''轮内腿序号(单仓=0)''');
CALL add_col_if_missing('backtest_trade', 'engine_kind',
     '`engine_kind` VARCHAR(16) DEFAULT NULL COMMENT ''引擎类型 SINGLE_LEG/MARTIN/GRID/LEGACY/STORED''');

-- 回测任务：轮级口径汇总（轮数 / 最长连亏轮数 / 平均每轮档数）
CALL add_col_if_missing('backtest_run', 'cycle_count',
     '`cycle_count` INT DEFAULT NULL COMMENT ''轮数(多腿策略=轮数;单仓=成交笔数)''');
CALL add_col_if_missing('backtest_run', 'max_losing_cycles',
     '`max_losing_cycles` INT DEFAULT NULL COMMENT ''最长连续净亏轮数(BASKET 门禁 c6)''');
CALL add_col_if_missing('backtest_run', 'avg_levels',
     '`avg_levels` DECIMAL(5,2) DEFAULT NULL COMMENT ''平均每轮档数(BASKET 门禁 c7)''');

-- ======================================================================
-- P2：实盘 cycle 执行器（马丁子模式）新增列 / 新表
--   放宽 GRID 档的 R2/R3/R4/R15；R1/R5/R6/R7/R9/R10/R11/R13/R14 两档共用同列（保持硬约束）
-- ======================================================================

-- 风控阈值：GRID 专用档（risk_config 加列；多腿/马丁执行器用）
CALL add_col_if_missing('risk_config', 'grid_max_volume',
     '`grid_max_volume` DECIMAL(10,2) DEFAULT NULL COMMENT ''GRID档单笔手数上限(须≥最高腿手数)''');
CALL add_col_if_missing('risk_config', 'grid_max_positions',
     '`grid_max_positions` INT DEFAULT NULL COMMENT ''GRID档最大持仓数(须≥最大腿数)''');
CALL add_col_if_missing('risk_config', 'grid_min_rr',
     '`grid_min_rr` DECIMAL(5,2) DEFAULT NULL COMMENT ''GRID档盈亏比地板''');
-- R4 放宽列：马丁单腿带「灾难兜底 SL」（默认 5.00USD），沿用 DEFAULT 档 2% 会整腿被拒
CALL add_col_if_missing('risk_config', 'grid_max_risk_pct',
     '`grid_max_risk_pct` DECIMAL(5,2) DEFAULT NULL COMMENT ''GRID档单笔风险预算%(覆盖R4 max_risk_pct;NULL→兜底25.00)''');

-- 实盘马丁轮次 / 轮腿 / 锁闸门（新建表；CREATE TABLE IF NOT EXISTS 天然幂等）
CREATE TABLE IF NOT EXISTS martin_cycle (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_no           INT           NOT NULL DEFAULT 0 COMMENT '轮编号(从1起)',
  symbol             VARCHAR(32)   NOT NULL,
  side               VARCHAR(8)    NOT NULL COMMENT 'BUY/SELL',
  mode               VARCHAR(16)   NOT NULL DEFAULT 'MARTIN',
  state              VARCHAR(24)   NOT NULL DEFAULT 'S2_ENTERED' COMMENT 'S2_ENTERED/S3_ADDED/S4_HOLDING/CLOSED…',
  level              INT           NOT NULL DEFAULT 1 COMMENT '当前腿数',
  base_volume        DECIMAL(10,2) DEFAULT NULL COMMENT '首腿手数',
  total_volume       DECIMAL(10,2) DEFAULT NULL COMMENT '总手数',
  avg_entry          DECIMAL(18,5) DEFAULT NULL COMMENT '加权均价',
  basket_tp          DECIMAL(18,5) DEFAULT NULL COMMENT '整轮止盈价',
  cycle_start_equity DECIMAL(18,2) DEFAULT NULL COMMENT '轮初权益(轮级硬止损基准)',
  session_id         BIGINT        DEFAULT NULL COMMENT '关联会话(1:1)',
  opened_at          DATETIME      DEFAULT NULL,
  closed_at          DATETIME      DEFAULT NULL,
  close_reason       VARCHAR(32)   DEFAULT NULL COMMENT 'TP/HARD_SL/DAY_LOCK/TOTAL_LOCK/MANUAL/END/MAX_LEVEL',
  realized_pnl       DECIMAL(18,2) DEFAULT NULL COMMENT '轮净利',
  update_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_state (state),
  KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实盘马丁轮次';

CREATE TABLE IF NOT EXISTS martin_cycle_leg (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  cycle_id     BIGINT        NOT NULL,
  level        INT           NOT NULL COMMENT '腿序号(1起)',
  ticket       BIGINT        DEFAULT NULL COMMENT '券商持仓票号',
  side         VARCHAR(8)    DEFAULT NULL COMMENT 'BUY/SELL',
  volume       DECIMAL(10,2) DEFAULT NULL,
  entry_price  DECIMAL(18,5) DEFAULT NULL,
  sl           DECIMAL(18,5) DEFAULT NULL COMMENT '该腿真实止损价',
  opened_at    DATETIME      DEFAULT NULL,
  closed_at    DATETIME      DEFAULT NULL,
  close_price  DECIMAL(18,5) DEFAULT NULL,
  realized_pnl DECIMAL(18,2) DEFAULT NULL,
  KEY idx_cycle (cycle_id),
  KEY idx_ticket (ticket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实盘马丁轮腿';

CREATE TABLE IF NOT EXISTS cycle_lock (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  lock_key    VARCHAR(32)   NOT NULL COMMENT '锁键(当前仅 TOTAL)',
  lock_until  DATETIME      DEFAULT NULL COMMENT '锁定到期时间',
  ref_equity  DECIMAL(18,2) DEFAULT NULL COMMENT '参照权益',
  ref_time    DATETIME      DEFAULT NULL COMMENT '参照时间(累计亏损统计起点)',
  update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_lock_key (lock_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实盘 cycle 锁闸门';

-- 锁档运行期设置（单行；前端「策略锁档」面板可改，0 或 NULL = 该档不启用/无上限）
CREATE TABLE IF NOT EXISTS cycle_setting (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  setting_key         VARCHAR(32)  NOT NULL COMMENT '设置键(当前仅 DEFAULT，单行)',
  max_single_loss_pct DECIMAL(6,2) DEFAULT NULL COMMENT '单轮最大亏损%(0或NULL=不启用)',
  max_daily_loss_pct  DECIMAL(6,2) DEFAULT NULL COMMENT '单日最大亏损%(0或NULL=不启用)',
  max_total_loss_pct  DECIMAL(6,2) DEFAULT NULL COMMENT '累计最大亏损%(0或NULL=不启用)',
  lock_hours          INT          DEFAULT NULL COMMENT '触发锁后的锁定时长(小时;NULL→兜底24)',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_setting_key (setting_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='实盘 cycle 锁档设置(人工可改)';

-- ======================================================================
-- 历史数据纠偏（幂等；仅修「账面 ↔ 真实成交」不一致，不新增/删除任何交易）
--   背景：旧实现只统计「执行器亲手平掉的腿」，券商单独 SL/TP 的腿未回填，
--         导致轮级 realized_pnl 失真（曾出现账面 +26.92 / 真实 −21.98）。
--   口径：真实盈亏 = trade_order 里同 ticket 的 SUM(profit + swap)。
-- ======================================================================

-- 1) 逐腿回填：按 ticket 汇总真实盈亏，仅填「当前为空或 0」且真实非 0 的腿
UPDATE martin_cycle_leg l
JOIN (
    SELECT o.ticket,
           SUM(o.profit + COALESCE(o.swap, 0)) AS realized,
           MAX(o.close_time)                   AS last_close
    FROM trade_order o
    WHERE o.status = 'CLOSED'
      AND o.ticket IS NOT NULL
    GROUP BY o.ticket
) t ON t.ticket = l.ticket
SET l.realized_pnl = ROUND(t.realized, 2),
    l.closed_at    = COALESCE(l.closed_at, t.last_close)
WHERE (l.realized_pnl IS NULL OR l.realized_pnl = 0)
  AND t.realized <> 0;

-- 2) 轮级汇总：按腿级真实盈亏重算已平仓轮次的轮净利
UPDATE martin_cycle c
JOIN (
    SELECT g.cycle_id, SUM(COALESCE(g.realized_pnl, 0)) AS realized
    FROM martin_cycle_leg g
    GROUP BY g.cycle_id
) s ON s.cycle_id = c.id
SET c.realized_pnl = ROUND(s.realized, 2)
WHERE c.state = 'CLOSED'
  AND (c.realized_pnl IS NULL OR c.realized_pnl <> ROUND(s.realized, 2));

-- 3) 平仓原因纠偏：轮净利与原因自相矛盾时按真实盈亏改写（保守，仅动这两个值）
UPDATE martin_cycle
SET close_reason = 'SL_BROKER'
WHERE state = 'CLOSED'
  AND realized_pnl < 0
  AND close_reason IN ('TP', 'TP_BROKER');

UPDATE martin_cycle
SET close_reason = 'TP_BROKER'
WHERE state = 'CLOSED'
  AND realized_pnl > 0
  AND close_reason = 'SL_BROKER';

-- 4) 锁档纠偏：2026-10-15 曾出现「三层亏损锁全被写成 0」= 关闭全部止损，
--    导致整轮止损不生效、5 条腿被券商逐个击穿（当日亏 -159.84）。
--    此处仅在「仍然全为 0/NULL」时恢复为《策略.txt》默认 5/3/10（幂等）。
--    注意：若你确实想关闭全部止损，请不要重跑本脚本，改用配置页单独设置。
UPDATE cycle_setting
SET max_single_loss_pct = 5.00,
    max_daily_loss_pct  = 3.00,
    max_total_loss_pct  = 10.00
WHERE setting_key = 'DEFAULT'
  AND COALESCE(max_single_loss_pct, 0) = 0
  AND COALESCE(max_daily_loss_pct, 0) = 0
  AND COALESCE(max_total_loss_pct, 0) = 0;

DROP PROCEDURE IF EXISTS add_col_if_missing;
