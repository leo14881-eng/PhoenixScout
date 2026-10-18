-- V2.6：模拟组合改为总预算 5,000 美元、最多 2 个、只在季度复核时补仓；推荐按期望值排序、带质量分和评级。
-- 规则变化后旧台账（V2.5，只有 1 天记录）不可比，按用户确认清空重建。
DROP TABLE IF EXISTS sim_trade;
DROP TABLE IF EXISTS sim_equity;

CREATE TABLE sim_trade (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    book          TEXT    NOT NULL,          -- track = 选币台账（每个推荐等额 1,000 美元）；portfolio = 模拟组合（5,000 美元、最多 2 个）
    coin_id       TEXT    NOT NULL,
    name          TEXT,
    path          TEXT    NOT NULL,          -- A / B
    grade         TEXT,                      -- 入场时评级 A / B
    quality       INTEGER,                   -- 入场时质量分 0–5
    researched    INTEGER NOT NULL DEFAULT 0,-- 入场时是否已有研究结果
    opened_day    TEXT    NOT NULL,
    entry_price   REAL    NOT NULL,
    amount_usd    REAL    NOT NULL,
    qty_initial   REAL    NOT NULL,
    qty           REAL    NOT NULL,          -- 剩余数量
    risk_usd      REAL    NOT NULL,          -- 金额 × 到防守价的跌幅
    ev            REAL,                      -- 入场期望值（统计估计）；样本不足或无法估值为空
    tp1           REAL,                      -- 估值目标：卖一半；无法估值为空（翻倍后启用回撤止盈）
    tp2           REAL,
    stop_loss     REAL    NOT NULL,
    reason        TEXT    NOT NULL,
    size_note     TEXT,
    status        TEXT    NOT NULL,          -- open / closed
    tp1_done      INTEGER NOT NULL DEFAULT 0,
    realized_usd  REAL    NOT NULL DEFAULT 0,
    peak_close    REAL    NOT NULL,
    min_close     REAL    NOT NULL,
    last_price    REAL    NOT NULL,
    last_day      TEXT    NOT NULL,
    closed_day    TEXT,
    exit_price    REAL,
    exit_reason   TEXT,
    pnl_usd       REAL,
    pnl_pct       REAL,
    r_multiple    REAL,
    horizons      TEXT    NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_sim_trade ON sim_trade(book, status, coin_id);

CREATE TABLE sim_equity (
    day       TEXT NOT NULL,
    book      TEXT NOT NULL,
    equity    REAL NOT NULL,
    invested  REAL NOT NULL,
    open_cnt  INTEGER NOT NULL,
    PRIMARY KEY (day, book)
);

-- 研究层原始结果（质量分、解锁数据从这里读）
CREATE TABLE research_raw (
    coin_id       TEXT NOT NULL,
    research_file TEXT NOT NULL,
    researched_at TEXT NOT NULL,
    json          TEXT NOT NULL,
    PRIMARY KEY (coin_id, research_file)
);

-- 每日推荐（A 级 + B 级观察），用于“今日淘汰”
CREATE TABLE rec_daily (
    scan_day  TEXT NOT NULL,
    coin_id   TEXT NOT NULL,
    name      TEXT,
    grade     TEXT NOT NULL,
    rank      INTEGER NOT NULL,
    PRIMARY KEY (scan_day, coin_id)
);

-- 每周策略回测
CREATE TABLE bt_run (
    run_day   TEXT PRIMARY KEY,
    finished_at TEXT NOT NULL,
    json      TEXT NOT NULL
);
CREATE TABLE bt_episode (
    run_day   TEXT NOT NULL,
    coin_id   TEXT NOT NULL,
    day       TEXT NOT NULL,
    entry     REAL NOT NULL,
    PRIMARY KEY (run_day, coin_id, day)
);
