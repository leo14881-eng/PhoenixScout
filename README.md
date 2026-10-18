# PhoenixScout（涅槃）

每日高倍潜力币研究扫描系统：寻找「长期严重超跌 + 近期真正转强 + 真实经济活动 + 可验证价值捕获 + 合理估值 + 可控稀释」的山寨币。

**只做三件事：找币、给出有来源的理由、推送消息。不建仓、不管持仓、不做任何交易。** 规则全文见 [`docs/规则说明_V2.3.md`](docs/规则说明_V2.3.md)，实现方案见 [`docs/技术方案.md`](docs/技术方案.md)。

## 当前阶段：P0 历史快照（截止 2026-10-31）

CoinGecko 免费接口只提供过去 365 天，2025-10-31 的数据在 2026-10-31 之后无法再取。

```bash
python3 scripts/p0/archive.py          # 归档当前前 2500 名的 365 天日线（可断点续跑）
python3 scripts/p0/build_snapshot.py   # 生成只读 snapshot_v1 + SHA-256 + 覆盖率报告
```

- 原始返回值：`data/raw/p0-archive/`（含请求 URL、请求时间、HTTP 状态）
- 快照：`data/snapshot/snapshot_v1.json`（只读，带校验值，绝不覆盖）

数据源全部为免费接口：CoinGecko（api.coingecko.com，无 key）、DefiLlama（api.llama.fi）。

## 部署（Windows，本机）

一次性安装（普通用户权限）：运行 `scripts\install-autoupdate.cmd`，注册三个计划任务：

- `PhoenixScout-Service`：登录后运行服务（端口 8100，只监听本机）
- `PhoenixScout-Watchdog`：每 2 分钟检查，服务停止或卡住自动拉起，Telegram 告知
- `PhoenixScout-AutoUpdate`：每 3 分钟检查 GitHub main，Windows CI 通过且本机构建测试通过才更新，失败自动回滚；
  每天确认接电源时不睡眠、合上盖子不睡眠

私密配置 `config\application-local.yml`（Telegram）不进仓库；可选 `config\coingecko-demo.key`（CoinGecko 免费 Demo key）。

开发端发布：`scripts/ship.sh`（推 staging → Windows+Linux CI 全绿 → 合并 main）。

## 云服务器（Vultr 东京，与 TrendPilot 同机，2026-10-18 起）

- 服务器：`/opt/phoenixscout`（运行）、`/opt/phoenixscout-src`（源码），用户 `phoenixscout`，`phoenixscout.service` 只监听 127.0.0.1:8100，JVM 上限 700M；
  `phoenixscout-update.timer` 每 3 分钟自动更新（main 有新提交 → 构建含测试 → 扫描空档换包 → 启动检查不过就回滚）并守护。同机的 TrendPilot 不受影响。
- 安装与维护脚本：`scripts/linux/`（install.sh、auto-update.sh、migrate-in.sh、status.sh）。仓库公开时匿名拉取；私有时用只读 deploy key。
- 电脑端只执行固定的维护动作：在 `app\server.request` 写 `check` / `repo` / `install` / `status` / `migrate-fresh` / `fallback`，结果在 `logs\server.log`。
  迁移后电脑上的服务停用（`app\disabled.flag`），守护、开机启动、自动更新都不再拉起它。
- Telegram 查询命令 `/ps_holdings`（模拟持仓）、`/ps_pnl`（模拟盈亏）、`/ps_today`、`/ps_status`、`/ps_help`：
  需要本项目单独的机器人，并在配置里设 `phoenixscout.telegram.commands-enabled: true`（一个机器人只能有一个程序接收消息）。
