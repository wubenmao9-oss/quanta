-- ======================================================================
-- quanta 数据库脚本（P0）
-- 目标数据库：MySQL 8（utf8mb4 / InnoDB）
-- 执行方式：mysql --host=127.0.0.1 --port=3307 -uroot -p < db/schema.sql
-- 幂等：全部 CREATE TABLE IF NOT EXISTS，可重复执行。
-- 结构：M1 建 sys_config；M2 追加其余 17 张表（设计 §9，共 18 张）。
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

-- ----------------------------------------------------------------------
-- 2) 策略库（含优化候选；target_rr / expected_win_rate / regime_type）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS strategy (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  name              VARCHAR(64)  NOT NULL,
  prompt            TEXT         NOT NULL COMMENT '策略Prompt',
  version           INT          NOT NULL DEFAULT 1,
  status            VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                    COMMENT 'DRAFT/CANDIDATE/ACTIVE/ARCHIVED',
  parent_id         BIGINT       DEFAULT NULL COMMENT '变体来源策略',
  origin            VARCHAR(16)  NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/LLM_OPT',
  approved          TINYINT      NOT NULL DEFAULT 0 COMMENT '人工批准才可置ACTIVE',
  regime_type       VARCHAR(8)   DEFAULT NULL COMMENT 'TREND/RANGE',
  target_rr         DECIMAL(5,2) DEFAULT NULL COMMENT '目标盈亏比(策略级)',
  expected_win_rate DECIMAL(5,2) DEFAULT NULL COMMENT '回测胜率',
  indicator_params  JSON         COMMENT '指标参数快照',
  description       VARCHAR(255) DEFAULT NULL,
  create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_name_version (name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='策略库';

-- ----------------------------------------------------------------------
-- 3) 指标参数
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS indicator_config (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  strategy_id    BIGINT      NOT NULL,
  indicator_name VARCHAR(32) NOT NULL COMMENT 'EMA/RSI/MACD/BOLL/ATR',
  params         JSON        NOT NULL COMMENT '如 {"period":14}',
  update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_strategy (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标参数';

-- ----------------------------------------------------------------------
-- 4) AI 会话
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_session (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_no    INT         NOT NULL COMMENT '动态窗口序号',
  session_type  VARCHAR(16) NOT NULL COMMENT 'AUTO/MANUAL',
  symbol        VARCHAR(32) NOT NULL,
  strategy_id   BIGINT      DEFAULT NULL,
  llm_config_id BIGINT      DEFAULT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/WAITING/CLOSED',
  linked_ticket BIGINT      DEFAULT NULL,
  message_count INT         NOT NULL DEFAULT 0,
  create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  close_time    DATETIME    DEFAULT NULL,
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI会话';

-- ----------------------------------------------------------------------
-- 5) 会话消息（前N条进记忆，全量留存）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_message (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id  BIGINT      NOT NULL,
  role        VARCHAR(16) NOT NULL COMMENT 'system/user/assistant/tool',
  content     TEXT        NOT NULL,
  tokens      INT         DEFAULT NULL,
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session (session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息';

-- ----------------------------------------------------------------------
-- 6) 结构化记忆卡
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_memory (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  scope        VARCHAR(16)  NOT NULL COMMENT 'GLOBAL/SYMBOL/STRATEGY',
  symbol       VARCHAR(32)  DEFAULT NULL,
  strategy_id  BIGINT       DEFAULT NULL,
  regime       VARCHAR(16)  DEFAULT NULL COMMENT 'TREND/RANGE',
  summary      VARCHAR(500) NOT NULL,
  tags         VARCHAR(255) DEFAULT NULL,
  source_trade BIGINT       DEFAULT NULL,
  create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_scope (scope, symbol),
  KEY idx_tags (tags)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='记忆卡';

-- ----------------------------------------------------------------------
-- 7) 交易计划（可选）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_plan (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id   BIGINT        NOT NULL,
  plan_type    VARCHAR(16)   NOT NULL COMMENT 'PRICE/PATTERN/TIME',
  target_price DECIMAL(18,5) DEFAULT NULL,
  invalid_cond VARCHAR(255)  DEFAULT NULL,
  expire_at    DATETIME      DEFAULT NULL,
  status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
               COMMENT 'PENDING/TRIGGERED/EXPIRED/CANCELLED',
  create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_status (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易计划';

-- ----------------------------------------------------------------------
-- 8) 订单流水
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_order (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  plan_id     BIGINT        DEFAULT NULL,
  session_id  BIGINT        DEFAULT NULL,
  trace_id    VARCHAR(32)   NOT NULL,
  ticket      BIGINT        DEFAULT NULL,
  symbol      VARCHAR(32)   NOT NULL,
  cmd         VARCHAR(8)    NOT NULL COMMENT 'BUY/SELL',
  volume      DECIMAL(10,2) NOT NULL,
  price       DECIMAL(18,5) DEFAULT NULL,
  sl          DECIMAL(18,5) DEFAULT NULL COMMENT '硬止损(必填)',
  tp          DECIMAL(18,5) DEFAULT NULL COMMENT '硬止盈(可空)',
  tp_mode     VARCHAR(8)    DEFAULT 'SOFT' COMMENT 'HARD/SOFT/NONE',
  trail_mode  VARCHAR(8)    DEFAULT 'NONE' COMMENT 'NONE/BREAKEVEN/TRAILING',
  status      VARCHAR(16)   NOT NULL COMMENT 'PENDING/FILLED/CLOSED/FAILED',
  retcode     INT           DEFAULT NULL,
  profit      DECIMAL(18,2) DEFAULT NULL,
  swap        DECIMAL(18,2) DEFAULT NULL COMMENT '隔夜费',
  open_time   DATETIME      DEFAULT NULL,
  close_price DECIMAL(18,5) DEFAULT NULL,
  close_time  DATETIME      DEFAULT NULL,
  KEY idx_trace (trace_id),
  KEY idx_symbol_time (symbol, open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单流水';

-- ----------------------------------------------------------------------
-- 9) AI 决策日志（可回放）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_decision_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id     BIGINT       NOT NULL,
  trace_id       VARCHAR(32)  NOT NULL,
  trigger_type   VARCHAR(16)  NOT NULL COMMENT 'T1..T8',
  input_snapshot JSON         COMMENT 'K线+指标+账户 快照',
  model          VARCHAR(64)  DEFAULT NULL,
  prompt_version INT          DEFAULT NULL,
  action         VARCHAR(16)  NOT NULL COMMENT 'OPEN/CLOSE/MODIFY/PLAN/WAIT/NOOP',
  action_params  JSON         COMMENT '动作参数',
  confidence     DECIMAL(4,2) DEFAULT NULL,
  reason         VARCHAR(500) DEFAULT NULL,
  tokens         INT          DEFAULT NULL,
  latency_ms     INT          DEFAULT NULL,
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_session (session_id, id),
  KEY idx_action (action, create_time),
  KEY idx_day (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI决策日志';

-- ----------------------------------------------------------------------
-- 10) 交易全周期报告
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_report (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id        BIGINT       NOT NULL,
  session_id      BIGINT       DEFAULT NULL,
  action_timeline JSON         COMMENT '分析→开仓→设SL/TP→平仓 每步简述',
  summary         VARCHAR(500) DEFAULT NULL,
  pnl             DECIMAL(18,2) DEFAULT NULL,
  result          VARCHAR(8)   DEFAULT NULL COMMENT 'WIN/LOSS/FLAT',
  reviewed        TINYINT      NOT NULL DEFAULT 0,
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='交易报告';

-- ----------------------------------------------------------------------
-- 11) K线缓存（分析与回测共用；回测需 M1 粒度）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS market_kline (
  id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  symbol    VARCHAR(32)   NOT NULL,
  timeframe VARCHAR(8)    NOT NULL,
  bar_time  DATETIME      NOT NULL,
  open      DECIMAL(18,5) DEFAULT NULL,
  high      DECIMAL(18,5) DEFAULT NULL,
  low       DECIMAL(18,5) DEFAULT NULL,
  close     DECIMAL(18,5) DEFAULT NULL,
  volume    BIGINT        DEFAULT NULL,
  UNIQUE KEY uk_bar (symbol, timeframe, bar_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='K线缓存';

-- ----------------------------------------------------------------------
-- 12) 回测任务（一个点差/滑点档位 = 一条记录，便于对比）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backtest_run (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  strategy_id    BIGINT        NOT NULL,
  symbol         VARCHAR(32)   NOT NULL,
  data_timeframe VARCHAR(8)    NOT NULL COMMENT '重放粒度,建议M1',
  start_time     DATETIME      DEFAULT NULL,
  end_time       DATETIME      DEFAULT NULL,
  params         JSON          COMMENT '参数快照(含target_rr)',
  spread_mode    VARCHAR(8)    DEFAULT NULL COMMENT 'OFF/FIXED/TIERS',
  spread         DECIMAL(10,2) DEFAULT NULL COMMENT '固定点差(points)',
  slippage       DECIMAL(10,2) DEFAULT NULL COMMENT '滑点(points)',
  swap_on        TINYINT       NOT NULL DEFAULT 1 COMMENT '计入隔夜费',
  mode           VARCHAR(16)   DEFAULT NULL COMMENT 'RULE/AGENT',
  win_rate       DECIMAL(5,2)  DEFAULT NULL,
  profit_factor  DECIMAL(8,2)  DEFAULT NULL,
  max_drawdown   DECIMAL(18,2) DEFAULT NULL,
  trade_count    INT           DEFAULT NULL,
  avg_hold_sec   INT           DEFAULT NULL,
  status         VARCHAR(16)   DEFAULT NULL,
  create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_strategy (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回测任务';

-- ----------------------------------------------------------------------
-- 13) 回测明细
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS backtest_trade (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id      BIGINT        NOT NULL,
  open_time   DATETIME      DEFAULT NULL,
  close_time  DATETIME      DEFAULT NULL,
  cmd         VARCHAR(8)    DEFAULT NULL,
  volume      DECIMAL(10,2) DEFAULT NULL,
  open_price  DECIMAL(18,5) DEFAULT NULL,
  close_price DECIMAL(18,5) DEFAULT NULL,
  sl          DECIMAL(18,5) DEFAULT NULL,
  tp          DECIMAL(18,5) DEFAULT NULL,
  profit      DECIMAL(18,2) DEFAULT NULL,
  swap        DECIMAL(18,2) DEFAULT NULL,
  hold_sec    INT           DEFAULT NULL,
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回测明细';

-- ----------------------------------------------------------------------
-- 14) 账户快照
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS account_snapshot (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  login         BIGINT        NOT NULL,
  balance       DECIMAL(18,2) DEFAULT NULL,
  equity        DECIMAL(18,2) DEFAULT NULL,
  margin        DECIMAL(18,2) DEFAULT NULL,
  free_margin   DECIMAL(18,2) DEFAULT NULL,
  profit        DECIMAL(18,2) DEFAULT NULL,
  currency      VARCHAR(8)    DEFAULT NULL,
  snapshot_time DATETIME      NOT NULL,
  KEY idx_time (snapshot_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账户快照';

-- ----------------------------------------------------------------------
-- 15) 风控阈值（仅人工可改；百分比制 + 盈亏比地板）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS risk_config (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  fixed_volume         DECIMAL(10,2) NOT NULL DEFAULT 0.01,
  max_volume           DECIMAL(10,2) NOT NULL DEFAULT 0.01,
  max_positions        INT           NOT NULL DEFAULT 3,
  max_daily_loss       DECIMAL(18,2) NOT NULL DEFAULT 50,
  max_risk_pct         DECIMAL(5,2)  NOT NULL DEFAULT 2.00 COMMENT '单笔风险占净值%',
  min_rr               DECIMAL(5,2)  NOT NULL DEFAULT 1.00 COMMENT '盈亏比地板',
  min_sl_spread_mult   DECIMAL(5,2)  NOT NULL DEFAULT 5.00 COMMENT '最小SL距离=点差×N',
  max_consecutive_loss INT           NOT NULL DEFAULT 3,
  cooldown_minutes     INT           NOT NULL DEFAULT 0 COMMENT '前期0=关闭',
  spread_limit         DECIMAL(10,2) NOT NULL DEFAULT 50 COMMENT '点差上限(动态兜底)',
  symbol_whitelist     VARCHAR(255)  NOT NULL DEFAULT 'GOLD',
  update_time          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='风控阈值';

-- ----------------------------------------------------------------------
-- 16) 通知记录
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notify_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  notify_type VARCHAR(32)  NOT NULL COMMENT 'OPEN/CLOSE/SYSTEM_ALERT/TOKEN_DEGRADE',
  channel     VARCHAR(16)  NOT NULL DEFAULT 'MAIL',
  subject     VARCHAR(255) DEFAULT NULL,
  content     TEXT         DEFAULT NULL,
  status      VARCHAR(16)  NOT NULL COMMENT 'SENT/FAILED',
  error       VARCHAR(255) DEFAULT NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知记录';

-- ----------------------------------------------------------------------
-- 17) 人工操作审计
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS operation_audit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  operator    VARCHAR(32)  NOT NULL DEFAULT 'LOCAL',
  op_type     VARCHAR(32)  NOT NULL COMMENT 'MANUAL_OPEN/CLOSE/MODIFY/PAUSE/TAKEOVER/TOKEN_DEGRADE',
  target      VARCHAR(64)  DEFAULT NULL,
  before_val  VARCHAR(255) DEFAULT NULL,
  after_val   VARCHAR(255) DEFAULT NULL,
  trace_id    VARCHAR(32)  DEFAULT NULL,
  create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人工介入审计';

-- ----------------------------------------------------------------------
-- 18) 系统运行状态（三态模式与降级态落库，重启后保持）
-- ----------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_state (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_mode           VARCHAR(16) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO/PAUSED/TAKEOVER',
  degraded           TINYINT     NOT NULL DEFAULT 0 COMMENT 'Token超限降级',
  token_used_today   BIGINT      NOT NULL DEFAULT 0,
  token_date         DATE        DEFAULT NULL,
  active_llm_id      BIGINT      DEFAULT NULL,
  active_strategy_id BIGINT      DEFAULT NULL,
  update_time        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统运行状态';