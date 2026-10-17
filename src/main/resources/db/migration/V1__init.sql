-- 日线：只存 UTC 00:00 时间戳点（= 前一 UTC 日收盘价）。缺失就是没有这一行，绝不填零。
CREATE TABLE price_daily (
    coin_id       TEXT    NOT NULL,
    day           TEXT    NOT NULL,          -- yyyy-MM-dd（UTC 00:00 点所在日期）
    price         REAL,
    market_cap    REAL,
    volume        REAL,
    source        TEXT    NOT NULL,          -- coingecko-market_chart / p0-archive
    requested_at  TEXT    NOT NULL,          -- 请求时间 UTC ISO
    PRIMARY KEY (coin_id, day)
);

-- 每次扫描的全市场行情快照（/coins/markets，非 00:00 收盘价，只作近似和广度用）
CREATE TABLE market_snapshot (
    scan_day          TEXT NOT NULL,
    coin_id           TEXT NOT NULL,
    symbol            TEXT,
    name              TEXT,
    rank              INTEGER,
    price             REAL,
    market_cap        REAL,
    fdv               REAL,
    circulating       REAL,
    total_supply      REAL,
    max_supply        REAL,
    volume_24h        REAL,
    requested_at      TEXT NOT NULL,
    PRIMARY KEY (scan_day, coin_id)
);

CREATE TABLE excluded_coin (
    coin_id    TEXT NOT NULL,
    category   TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (coin_id, category)
);

CREATE TABLE scan_run (
    scan_day        TEXT PRIMARY KEY,
    started_at      TEXT NOT NULL,
    finished_at     TEXT,
    status          TEXT NOT NULL,           -- running / ok / partial / failed
    quality_json    TEXT,
    summary         TEXT
);

-- 跟踪状态：新发现 / 持续A级 / 降级 / 淘汰；评级 A/B/C
CREATE TABLE candidate_state (
    scan_day   TEXT NOT NULL,
    coin_id    TEXT NOT NULL,
    path       TEXT NOT NULL,                -- A / B / C / NEW
    grade      TEXT NOT NULL,
    detail     TEXT NOT NULL,                -- JSON：关键数字与理由
    PRIMARY KEY (scan_day, coin_id, path)
);

CREATE TABLE research_evidence (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    coin_id     TEXT NOT NULL,
    item        TEXT NOT NULL,               -- 对应 A 级 9 项中的哪一项
    claim       TEXT NOT NULL,
    url         TEXT NOT NULL,
    source_date TEXT,
    verified    INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL
);

CREATE TABLE notification (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    kind       TEXT NOT NULL,
    coin_id    TEXT,
    scan_day   TEXT,
    sent_at    TEXT NOT NULL,
    ok         INTEGER NOT NULL
);

CREATE TABLE api_call_stats (
    scan_day TEXT NOT NULL,
    api      TEXT NOT NULL,
    ok       INTEGER NOT NULL,
    failed   INTEGER NOT NULL,
    PRIMARY KEY (scan_day, api)
);

-- 接口响应缓存：同一请求在 TTL 内直接用本地结果，不再请求第三方（gzip 压缩存储）
CREATE TABLE api_cache (
    cache_key   TEXT PRIMARY KEY,
    body_gz     BLOB NOT NULL,
    fetched_at  INTEGER NOT NULL            -- epoch 毫秒
);

-- 已知 404（改名/下架）的币：7 天内不再请求
CREATE TABLE api_negative (
    cache_key   TEXT PRIMARY KEY,
    status      INTEGER NOT NULL,
    fetched_at  INTEGER NOT NULL
);

CREATE TABLE kv (
    k  TEXT PRIMARY KEY,
    v  TEXT NOT NULL
);
