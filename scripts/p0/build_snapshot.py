#!/usr/bin/env python3
"""PhoenixScout P0：由归档的 365 天日线生成只读快照 snapshot_vN。

输入：data/raw/p0-archive/（archive.py 的产物，只读）
输出：data/snapshot/snapshot_vN.json、snapshot_vN.json.sha256、snapshot_vN-覆盖率报告.md

规则（对应 V2.2 第六、七、九条）：
- 只取 UTC 00:00 时间戳的数据点；目标日期不存在 00:00 点 -> 标记“时间戳不符/缺失”，绝不用邻近日期替代。
- 缺失即缺失，不填零。
- 已存在的版本绝不覆盖；需修正时用 --version 生成新版本并写明原因。
- 排除规则按 CoinGecko 分类（稳定币/包装/跨链映射/LST/LRT/平台币/代币化黄金）标记，
  其余（诈骗、操纵、停更）需人工/研究层判定，快照内只记录不判定。

价格口径说明：CoinGecko 日线 00:00 UTC 点 = 当日 00:00 的价格，即前一 UTC 日的收盘价。
"""
import argparse, glob, gzip, hashlib, json, os, pathlib, stat, sys, datetime as dt

ROOT = pathlib.Path(__file__).resolve().parents[2]
RAW = ROOT / "data" / "raw" / "p0-archive"
SNAP = ROOT / "data" / "snapshot"
DATES = ["2025-10-31", "2026-07-31"]
TOP_K = 500


def load(p):
    with gzip.open(p, "rt", encoding="utf-8") as f:
        return json.load(f)


def ms(d):
    return int(dt.datetime.strptime(d, "%Y-%m-%d").replace(tzinfo=dt.timezone.utc).timestamp() * 1000)


def point(series, d):
    """返回 (value, flag)。只接受精确的 00:00 UTC 时间戳。"""
    t = ms(d)
    for ts, v in series or []:
        if ts == t:
            return (v, "ok") if v is not None else (None, "missing")
    near = [ts for ts, _ in (series or []) if abs(ts - t) < 86_400_000]
    return None, ("timestamp_mismatch" if near else "missing")


def excluded_ids():
    out = {}
    for f in glob.glob(str(RAW / "categories" / "*-p*.json.gz")):
        cat = os.path.basename(f).rsplit("-p", 1)[0]
        w = load(f)
        if w.get("http_status") == 200:
            for c in w["body"]:
                out.setdefault(c["id"], []).append(cat)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default="v1")
    ap.add_argument("--reason", default="初版快照")
    a = ap.parse_args()

    out = SNAP / f"snapshot_{a.version}.json"
    if out.exists():
        sys.exit(f"拒绝覆盖：{out} 已存在。修正请使用新版本号 --version v2 --reason '...'")

    uni = load(RAW / "universe.json.gz")
    excl = excluded_ids()
    if not excl:
        print("警告：分类排除数据缺失，排除规则未能执行（会在报告中写明）")
    fails = {}
    fp = RAW / "failures.jsonl"
    if fp.exists():
        for line in fp.read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            fails[r["id"]] = r

    rows, stats = [], {"universe": len(uni["coins"]), "archived": 0, "not_archived": 0,
                       "excluded_by_category": 0}
    for c in uni["coins"]:
        hf = RAW / "history" / f"{c['id']}.json.gz"
        row = {"id": c["id"], "symbol": c["symbol"], "name": c["name"],
               "rank_at_archive": c["rank_at_archive"],
               "excluded_categories": excl.get(c["id"], [])}
        if row["excluded_categories"]:
            stats["excluded_by_category"] += 1
        if not hf.exists():
            stats["not_archived"] += 1
            row["status"] = "not_archived"
            row["failure"] = fails.get(c["id"])
            rows.append(row)
            continue
        stats["archived"] += 1
        w = load(hf)
        body = w["body"]
        row["status"] = "archived"
        row["requested_at_utc"] = w["requested_at_utc"]
        row["first_point_utc"] = (dt.datetime.fromtimestamp(body["prices"][0][0] / 1000, dt.timezone.utc)
                                  .strftime("%Y-%m-%d") if body.get("prices") else None)
        for d in DATES:
            p, pf = point(body.get("prices"), d)
            m, mf = point(body.get("market_caps"), d)
            row[d] = {"price": p, "price_flag": pf, "market_cap": m, "market_cap_flag": mf}
        rows.append(row)

    # 按 2025-10-31 市值重排（排除分类币、市值缺失或为 0 的不参与排名）
    base = DATES[0]
    rankable = [r for r in rows if r["status"] == "archived" and not r["excluded_categories"]
                and r[base]["market_cap_flag"] == "ok" and r[base]["market_cap"]]
    rankable.sort(key=lambda r: r[base]["market_cap"], reverse=True)
    for i, r in enumerate(rankable, 1):
        r["rank_2025_10_31"] = i
    top = [r["id"] for r in rankable[:TOP_K]]

    snap = {
        "version": a.version, "reason": a.reason,
        "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "source": "CoinGecko 免费接口 /coins/{id}/market_chart days=365 interval=daily",
        "price_basis": "UTC 00:00 时间戳数据点（= 前一 UTC 日收盘价）",
        "dates": DATES, "universe_top_n": uni["top_n"], "universe_created_at_utc": uni["created_at_utc"],
        "stats": {**stats, "rankable_2025_10_31": len(rankable), "historical_top_k": len(top)},
        "historical_top_k_ids": top,
        "known_limitations": [
            "已退市/归零/改名的币无法从当前接口获取，历史成分存在残余幸存者偏差。",
            f"初始名单为归档当时市值前 {uni['top_n']} 名；当时排名在此之外的币不在快照中。",
            "排除规则仅按 CoinGecko 分类执行；诈骗/操纵/停更需研究层判定。",
        ],
        "coins": rows,
    }
    SNAP.mkdir(parents=True, exist_ok=True)
    data = json.dumps(snap, ensure_ascii=False, indent=1, sort_keys=False).encode("utf-8")
    out.write_bytes(data)
    h = hashlib.sha256(data).hexdigest()
    (SNAP / f"snapshot_{a.version}.json.sha256").write_text(f"{h}  snapshot_{a.version}.json\n")
    os.chmod(out, stat.S_IREAD | stat.S_IRGRP | stat.S_IROTH)

    def cnt(d, k, v):
        return sum(1 for r in rows if r["status"] == "archived" and r[d][k] == v)

    rep = [f"# 快照 {a.version} 覆盖率报告", "",
           f"- 生成时间：{snap['created_at_utc']}", f"- SHA-256：`{h}`", f"- 原因：{a.reason}", "",
           "| 项目 | 数量 |", "|---|---|",
           f"| 初始名单（归档时市值前 N） | {stats['universe']} |",
           f"| 成功归档 | {stats['archived']} |",
           f"| 未归档（失败/404） | {stats['not_archived']} |",
           f"| 按分类排除 | {stats['excluded_by_category']} |",
           f"| 2025-10-31 可排名 | {len(rankable)} |",
           f"| 历史成分（取前 {TOP_K}） | {len(top)} |"]
    for d in DATES:
        rep.append(f"| {d} 价格 ok / 时间戳不符 / 缺失 | {cnt(d,'price_flag','ok')} / "
                   f"{cnt(d,'price_flag','timestamp_mismatch')} / {cnt(d,'price_flag','missing')} |")
    if len(top) < TOP_K:
        rep += ["", f"**注意：仅完成 {len(top)} 个历史成分，未达到前 {TOP_K} 名，不得宣称完成前 {TOP_K} 名快照。**"]
    rep += ["", "## 已知局限", *[f"- {x}" for x in snap["known_limitations"]]]
    (SNAP / f"snapshot_{a.version}-覆盖率报告.md").write_text("\n".join(rep) + "\n", encoding="utf-8")
    print("\n".join(rep))


if __name__ == "__main__":
    main()
