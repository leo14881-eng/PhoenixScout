-- 重点观察名单的每日指标（用户 2026-10-20 要求把 ARB 加入重点观察）
CREATE TABLE watch_metric (
    day              TEXT NOT NULL,
    coin_id          TEXT NOT NULL,
    price            REAL,
    market_cap       REAL,
    total30          REAL,     -- 近 30 天收入（自身 + 合作链分成估算）
    total_prev30     REAL,
    holders30        REAL,
    revenue_growing  INTEGER,  -- 1 连续两次环比增长 / 0 否 / NULL 待确认
    holders_positive INTEGER NOT NULL,
    PRIMARY KEY (day, coin_id)
);
