# PhoenixScout（涅槃）

每日高倍潜力币研究扫描系统：寻找「长期严重超跌 + 近期真正转强 + 真实经济活动 + 可验证价值捕获 + 合理估值 + 可控稀释」的山寨币。

**只研究、筛选、跟踪、通知，不做任何交易。** 规则全文见 [`docs/规则说明_V2.2.md`](docs/规则说明_V2.2.md)，实现方案见 [`docs/技术方案.md`](docs/技术方案.md)。

## 当前阶段：P0 历史快照（截止 2026-10-31）

CoinGecko 免费接口只提供过去 365 天，2025-10-31 的数据在 2026-10-31 之后无法再取。

```bash
python3 scripts/p0/archive.py          # 归档当前前 2500 名的 365 天日线（可断点续跑）
python3 scripts/p0/build_snapshot.py   # 生成只读 snapshot_v1 + SHA-256 + 覆盖率报告
```

- 原始返回值：`data/raw/p0-archive/`（含请求 URL、请求时间、HTTP 状态）
- 快照：`data/snapshot/snapshot_v1.json`（只读，带校验值，绝不覆盖）

数据源全部为免费接口：CoinGecko（api.coingecko.com，无 key）、DefiLlama（api.llama.fi）。
