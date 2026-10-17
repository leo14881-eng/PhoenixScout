-- V2.5 长线模拟台账（只记账，不下真实订单）。旧的 sim_position 只有建立当天的数据，按用户确认清空重建。
DROP TABLE IF EXISTS sim_position;

CREATE TABLE sim_trade (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    book          TEXT    NOT NULL,          -- track = 选币准确度（每个推荐等额 1,000 美元）；portfolio = 模拟组合（按风险定仓）
    coin_id       TEXT    NOT NULL,
    name          TEXT,
    path          TEXT    NOT NULL,          -- A / B
    opened_day    TEXT    NOT NULL,
    entry_price   REAL    NOT NULL,
    amount_usd    REAL    NOT NULL,
    qty_initial   REAL    NOT NULL,
    qty           REAL    NOT NULL,          -- 剩余数量
    risk_usd      REAL    NOT NULL,          -- 入场时承担的风险（金额 × 到防守价的跌幅）
    ratio         REAL    NOT NULL,          -- 入场盈亏比
    tp1           REAL    NOT NULL,          -- 第一目标：卖一半
    tp2           REAL,                      -- 长期目标（路径 B 基准 36 个月）；没有则剩余一半用回撤止盈
    stop_loss     REAL    NOT NULL,
    reason        TEXT    NOT NULL,
    size_note     TEXT,
    status        TEXT    NOT NULL,          -- open / closed
    tp1_done      INTEGER NOT NULL DEFAULT 0,
    realized_usd  REAL    NOT NULL DEFAULT 0,-- 已卖出部分的盈亏
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
    horizons      TEXT    NOT NULL DEFAULT '{}' -- {"30":{"coin":..,"btc":..,"alt":..},...}
);
CREATE INDEX idx_sim_trade ON sim_trade(book, status, coin_id);

CREATE TABLE sim_equity (
    day       TEXT NOT NULL,
    book      TEXT NOT NULL,
    equity    REAL NOT NULL,       -- 按收盘价计的权益（本金 + 已实现 + 浮动）
    invested  REAL NOT NULL,
    open_cnt  INTEGER NOT NULL,
    PRIMARY KEY (day, book)
);
