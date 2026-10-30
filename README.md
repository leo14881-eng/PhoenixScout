# research-data（研究层中转分支，只放数据，不放代码）

PhoenixScout 服务器和 Claude 研究任务通过这个分支交换文件，不经过用户电脑（2026-10-30 起）。

- `candidates/candidates-<日期>.json`：服务器每日扫描的候选（最新 7 份），由服务器 `scripts/linux/relay.sh` 上传。
- `server-research.txt`：服务器 `data/research/` 里已有的研究文件名（用于判断 7 天内是否研究过）。
- `research/research-<UTC日期>-<coin_id>.json`：Claude 研究任务写入，格式见 main 分支 `docs/研究层说明.md`。
  服务器每 3 分钟拉回 `data/research/`，只新增、不覆盖同名文件；评级和推送由服务按代码规则完成。

这个分支没有 CI，也不会被部署。系统只做研究，不做任何交易。
