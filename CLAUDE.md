# PhoenixScout — Claude 工作说明

- 回复用中文。
- 系统只做三件事：找币、给出有来源的理由、推送消息。**不做建仓/持仓/止盈/换仓，不实现任何交易功能**。
- 规则以 `docs/规则说明_V2.3.md` 为准（V2.2 仅存档）；实现方案见 `docs/技术方案.md`。规则修改需用户确认，并更新规则文件版本号。
- 只用免费接口（CoinGecko 免费 Demo key，每月 1 万次、本月用到 9000 次自动退回公共接口；DefiLlama）；端口 8100；单服务单限流器，遵守 429/Retry-After，SQLite 缓存。
- 数据纪律：缺失即缺失（不填零）、不用邻近日期冒充目标日期、所有价格带来源与请求时间、数据源冲突要记录。
- 快照 `data/snapshot/snapshot_vN.json` 只读；校验失败时停止相关计算并报告，绝不重建覆盖；修正只能出新版本。
- A 级只能由代码 checklist 判定：9 项硬性条件每项必须有数据字段或「来源 url + 日期」，否则上限 B。
- 绝不读取或复制 `config/application-local.yml`（存放 Telegram token 等私密配置）。
