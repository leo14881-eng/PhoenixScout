-- 模拟仓（只记账，不下任何真实订单），用于复盘推荐质量
CREATE TABLE sim_position (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    coin_id       TEXT    NOT NULL,
    name          TEXT,
    path          TEXT    NOT NULL,          -- A / B
    opened_day    TEXT    NOT NULL,          -- 入场日（UTC，按当日 00:00 收盘价成交）
    entry_price   REAL    NOT NULL,
    amount_usd    REAL    NOT NULL,          -- 入场金额（含手续费前）
    qty           REAL    NOT NULL,
    take_profit   REAL    NOT NULL,
    stop_loss     REAL    NOT NULL,
    ratio         REAL,                      -- 入场时盈亏比
    reason        TEXT    NOT NULL,          -- 入场原因
    status        TEXT    NOT NULL,          -- open / closed
    last_price    REAL,
    last_day      TEXT,
    closed_day    TEXT,
    exit_price    REAL,
    exit_reason   TEXT,                      -- 止盈 / 止损 / 保本止损 / 到期
    pnl_usd       REAL,
    pnl_pct       REAL
);
CREATE INDEX idx_sim_status ON sim_position(status, coin_id);
