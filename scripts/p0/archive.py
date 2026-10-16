#!/usr/bin/env python3
"""PhoenixScout P0 归档器：在 2025-10-31 数据超出 CoinGecko 免费 365 天窗口前，
把当前市值前 TOP_N 个币的完整 365 天日线（价格/市值/成交量）原样存档。

- 可断点续跑：已存在的币直接跳过。
- 只追加、不覆盖：原始返回值连同请求 URL、请求时间、HTTP 状态一起 gzip 保存。
- 速率：默认每次请求间隔 MIN_INTERVAL 秒；遇 429 按 Retry-After / 指数退避。
- 缺失即记录为缺失（failures.jsonl），绝不填充。
"""
import gzip, json, os, sys, time, math, pathlib, datetime as dt
import requests

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "data" / "raw" / "p0-archive"
API = "https://api.coingecko.com/api/v3"
TOP_N = int(os.environ.get("TOP_N", "2500"))
MIN_INTERVAL = float(os.environ.get("MIN_INTERVAL", "6.5"))
EXCLUDE_CATEGORIES = [
    "stablecoins", "wrapped-tokens", "bridged-tokens", "liquid-staking-tokens",
    "liquid-restaking-tokens", "exchange-based-tokens", "tokenized-gold",
]

_last = 0.0
S = requests.Session()
S.headers["User-Agent"] = "PhoenixScout-P0/1.0"
# 可选：CoinGecko 免费 Demo key（30 次/分钟）。从文件读取，不写进代码或日志。
_KEY_FILE = os.environ.get("COINGECKO_DEMO_KEY_FILE", str(ROOT / "config" / "coingecko-demo.key"))
if os.path.exists(_KEY_FILE):
    S.headers["x-cg-demo-api-key"] = open(_KEY_FILE, encoding="utf-8").read().strip()
    if "MIN_INTERVAL" not in os.environ:
        MIN_INTERVAL = 2.2


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def log(msg):
    print(f"{now()} {msg}", flush=True)


def get(path, params):
    """返回 (status, body, url, requested_at)。404 直接返回，不重试。"""
    global _last
    for attempt in range(1, 9):
        wait = MIN_INTERVAL - (time.time() - _last)
        if wait > 0:
            time.sleep(wait)
        _last = time.time()
        at = now()
        try:
            r = S.get(API + path, params=params, timeout=40)
        except requests.RequestException as e:
            log(f"network error {path} attempt {attempt}: {e}")
            time.sleep(min(300, 15 * attempt))
            continue
        if r.status_code == 200:
            return 200, r.json(), r.url, at
        if r.status_code == 404:
            return 404, r.text[:500], r.url, at
        if r.status_code == 429 or r.status_code >= 500:
            ra = r.headers.get("Retry-After")
            sleep = int(ra) if ra and ra.isdigit() else min(600, 65 * attempt)
            log(f"HTTP {r.status_code} {path} -> sleep {sleep}s (attempt {attempt})")
            time.sleep(sleep)
            continue
        return r.status_code, r.text[:500], r.url, at
    return -1, "gave up after retries", API + path, now()


def save(path: pathlib.Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with gzip.open(tmp, "wt", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.replace(tmp, path)


def wrap(status, body, url, at):
    return {"source": "coingecko-free", "request_url": url, "requested_at_utc": at,
            "http_status": status, "body": body}


def universe():
    f = OUT / "universe.json.gz"
    if f.exists():
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            return json.load(fh)["coins"]
    coins = []
    for page in range(1, math.ceil(TOP_N / 250) + 1):
        st, body, url, at = get("/coins/markets", {"vs_currency": "usd", "order": "market_cap_desc",
                                                   "per_page": 250, "page": page})
        save(OUT / "markets" / f"page-{page:02d}.json.gz", wrap(st, body, url, at))
        if st != 200:
            log(f"markets page {page} failed: {st}")
            sys.exit(2)
        coins += [{"id": c["id"], "symbol": c["symbol"], "name": c["name"],
                   "rank_at_archive": c.get("market_cap_rank")} for c in body]
        log(f"markets page {page}: total {len(coins)}")
    coins = coins[:TOP_N]
    save(f, {"created_at_utc": now(), "top_n": TOP_N, "coins": coins})
    return coins


def categories():
    d = OUT / "categories"
    if not (d / "list.json.gz").exists():
        save(d / "list.json.gz", wrap(*get("/coins/categories/list", {})))
    for cat in EXCLUDE_CATEGORIES:
        for page in range(1, 5):
            f = d / f"{cat}-p{page}.json.gz"
            if f.exists():
                with gzip.open(f, "rt", encoding="utf-8") as fh:
                    prev = json.load(fh)
                if prev.get("http_status") != 200 or len(prev.get("body") or []) < 250:
                    break  # 该分类已取完（上次最后一页不满 250）
                continue
            st, body, url, at = get("/coins/markets", {"vs_currency": "usd", "category": cat,
                                                       "per_page": 250, "page": page})
            save(f, wrap(st, body, url, at))
            if st != 200 or not body or len(body) < 250:
                break


def history(coins):
    hd = OUT / "history"
    fail = OUT / "failures.jsonl"
    done = sum(1 for c in coins if (hd / f"{c['id']}.json.gz").exists())
    log(f"history: {done}/{len(coins)} already archived")
    for i, c in enumerate(coins, 1):
        f = hd / f"{c['id']}.json.gz"
        if f.exists():
            continue
        st, body, url, at = get(f"/coins/{c['id']}/market_chart",
                                {"vs_currency": "usd", "days": 365, "interval": "daily"})
        if st == 200:
            save(f, wrap(st, body, url, at))
            done += 1
        else:
            with open(fail, "a", encoding="utf-8") as fh:
                fh.write(json.dumps({"id": c["id"], "status": st, "detail": str(body)[:300],
                                     "requested_at_utc": at}) + "\n")
        if i % 25 == 0:
            log(f"history progress {done}/{len(coins)} (scanned {i})")
            (OUT / "progress.json").write_text(json.dumps(
                {"updated_at_utc": now(), "archived": done, "universe": len(coins), "scanned": i}))
    (OUT / "progress.json").write_text(json.dumps(
        {"updated_at_utc": now(), "archived": done, "universe": len(coins),
         "scanned": len(coins), "finished": True}))
    log(f"history finished: {done}/{len(coins)}")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    log(f"P0 archive start TOP_N={TOP_N} interval={MIN_INTERVAL}s demo_key={'x-cg-demo-api-key' in S.headers} -> {OUT}")
    cs = universe()
    history(cs)
    categories()
    log("P0 archive done")
