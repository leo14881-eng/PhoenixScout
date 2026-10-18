package com.phoenixscout.web;

import com.phoenixscout.job.ScanScheduler;
import com.phoenixscout.market.CoinGeckoClient;
import com.phoenixscout.scan.DailyScanService;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 本机状态页 http://127.0.0.1:8100/ ：服务、扫描、快照、接口额度、模拟仓一页看全。每 60 秒自动刷新。 */
@RestController
public class StatusPageController {

    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private final DailyScanService scan;
    private final ScanScheduler scheduler;
    private final JdbcTemplate jdbc;
    private final CoinGeckoClient cg;
    private final Path dataDir;
    private final com.phoenixscout.sim.LiveSimService live;
    private final Instant started = Instant.now();

    public StatusPageController(DailyScanService scan, ScanScheduler scheduler, JdbcTemplate jdbc, CoinGeckoClient cg, Path dataDir,
                                com.phoenixscout.sim.LiveSimService live) {
        this.live = live;
        this.scan = scan;
        this.scheduler = scheduler;
        this.jdbc = jdbc;
        this.cg = cg;
        this.dataDir = dataDir;
    }

    static String esc(Object o) {
        return o == null ? "—" : HtmlUtils.htmlEscape(o.toString());
    }

    static String t(Instant i) {
        return i == null ? "—" : T.format(i);
    }

    static String money(Object o) {
        return o == null ? "—" : String.format(Locale.ROOT, "%+.0f", ((Number) o).doubleValue());
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String page() throws Exception {
        String version = "—";
        Path v = Path.of("app", "version.txt");
        if (Files.exists(v)) version = Files.readString(v).trim();
        if (version.length() > 7) version = version.substring(0, 7);
        boolean stuck = scan.isRunning() && scan.lastStart() != null && Instant.now().minusSeconds(4 * 3600).isAfter(scan.lastStart());
        String ok = stuck ? "<b class=bad>卡住</b>" : "<b class=ok>正常运行</b>";

        List<Map<String, Object>> runs = jdbc.queryForList("SELECT scan_day, status, summary, finished_at FROM scan_run ORDER BY scan_day DESC LIMIT 5");
        boolean snap = Files.exists(dataDir.resolve("snapshot").resolve("snapshot_v1.json"));
        List<String> research = jdbc.queryForList("SELECT coin_id || ' → ' || grade || '（' || substr(graded_at,1,16) || '）' FROM research_grade ORDER BY id DESC LIMIT 5", String.class);

        StringBuilder h = new StringBuilder("""
                <!doctype html><html lang=zh><head><meta charset=utf-8><meta http-equiv=refresh content=60>
                <meta name=viewport content="width=device-width,initial-scale=1"><title>PhoenixScout 状态</title>
                <style>body{font:14px/1.5 system-ui,"Microsoft YaHei",sans-serif;margin:16px;color:#222;background:#fafafa}
                h1{font-size:20px}h2{font-size:16px;margin-top:22px}table{border-collapse:collapse;width:100%;background:#fff}
                td,th{border:1px solid #ddd;padding:4px 8px;text-align:left;vertical-align:top}th{background:#f0f0f0}
                .ok{color:#18794e}.bad{color:#c62828}.muted{color:#777}</style></head><body>
                <h1>PhoenixScout 状态</h1>""");
        h.append("<table><tr><th>服务</th><td>").append(ok).append("（启动于 ").append(t(started)).append("，版本 ").append(esc(version)).append("）</td></tr>");
        h.append("<tr><th>每日扫描</th><td>").append(scan.isRunning() ? "进行中（开始于 " + t(scan.lastStart()) + "）" : "空闲")
                .append("；上次结果：").append(esc(scan.lastStatus())).append("，完成于 ").append(t(scan.lastFinish()))
                .append(scan.lastError() == null ? "" : "，错误：" + esc(scan.lastError())).append("；下次：每天 07:40</td></tr>");
        h.append("<tr><th>历史快照</th><td>").append(snap ? "snapshot_v1 已生成" : "未生成").append("（归档状态：").append(esc(scheduler.p0State())).append("）</td></tr>");
        h.append("<tr><th>CoinGecko</th><td>").append(cg.usingDemoKey() ? "Demo key 生效" : "公共接口（无 key）")
                .append("；本月 key 已用 ").append(cg.keyUsedThisMonth()).append(" / ").append(CoinGeckoClient.MONTHLY_KEY_BUDGET).append("</td></tr>");
        h.append("<tr><th>研究层最近结果</th><td>").append(research.isEmpty() ? "<span class=muted>还没有研究结果</span>" : esc(String.join("；", research))).append("</td></tr></table>");

        h.append("<h2>最近扫描</h2><table><tr><th>日期</th><th>状态</th><th>结果</th><th>完成时间</th></tr>");
        for (var r : runs) {
            h.append("<tr><td>").append(esc(r.get("scan_day"))).append("</td><td>").append(esc(r.get("status"))).append("</td><td>")
                    .append(esc(r.get("summary"))).append("</td><td>").append(esc(r.get("finished_at"))).append("</td></tr>");
        }
        h.append("</table>");

        Map<String, Object> sim = live.snapshot();
        if (sim.get("warning") != null) h.append("<p class=bad>").append(esc(sim.get("warning"))).append("</p>");
        for (String key : List.of("portfolio", "track")) {
            Map<?, ?> b = (Map<?, ?>) sim.get(key);
            Map<?, ?> rv = (Map<?, ?>) b.get("review");
            if (key.equals("portfolio")) {
                h.append(String.format(Locale.ROOT, "<h2>模拟组合（5,000 美元、最多 2 个，季度复核补仓）</h2><p>本金 %s｜实时权益 <b>%s</b>（%s%%）｜现金 %s｜持仓 %s 个，成本 %s，浮动 <b>%s</b>｜已平仓 %s 笔｜最大回撤 %s%%</p>",
                        b.get("startEquity"), b.get("equityLive"), b.get("returnPct"), b.get("cash"), b.get("open"), b.get("invested"),
                        money(b.get("unrealizedUsd")), b.get("closedTrades"), rv.get("maxDrawdownPct")));
            } else {
                h.append(String.format(Locale.ROOT, "<h2>选币台账（选得准不准，每个推荐等额 1,000 美元）</h2><p>记录 %s 笔，持仓 %s，浮动 %s｜已平仓 %s 笔，已实现 %s｜估值目标命中 %s%%，平均 %sR%s</p>",
                        rv.get("trades"), b.get("open"), money(b.get("unrealizedUsd")), b.get("closedTrades"), money(b.get("realizedClosedUsd")),
                        rv.get("tp1HitPct") == null ? "—" : rv.get("tp1HitPct"), rv.get("avgR") == null ? "—" : rv.get("avgR"),
                        Boolean.TRUE.equals(rv.get("sampleEnough")) ? "" : "（样本不足 30 笔，结论不可靠）"));
                Map<?, ?> hz = (Map<?, ?>) rv.get("horizons");
                if (!hz.isEmpty()) {
                    h.append("<table><tr><th>入场后</th><th>笔数</th><th>平均收益</th><th>跑赢 BTC</th><th>相对山寨中位数</th></tr>");
                    hz.forEach((k, hv) -> {
                        Map<?, ?> o = (Map<?, ?>) hv;
                        h.append("<tr><td>").append(esc(k)).append("</td><td>").append(esc(o.get("n"))).append("</td><td>").append(esc(o.get("avgReturnPct")))
                                .append("%</td><td>").append(esc(o.get("beatBtcPct"))).append("%</td><td>").append(esc(o.get("excessVsAltPct"))).append("%</td></tr>");
                    });
                    h.append("</table><br>");
                }
            }
            h.append("<table><tr><th>币</th><th>路径</th><th>入场日</th><th>入场价</th><th>金额</th><th>风险</th><th>现价</th><th>盈亏</th><th>R</th>"
                    + "<th>估值目标（距离）</th><th>防守（距离）</th><th>评级 / 质量分 / 入场期望值</th><th>入场原因</th></tr>");
            for (Object o : (List<?>) b.get("positions")) {
                Map<?, ?> p = (Map<?, ?>) o;
                double pnl = ((Number) p.get("pnlUsd")).doubleValue();
                h.append("<tr><td>").append(esc(p.get("name"))).append(Boolean.TRUE.equals(p.get("halfSold")) ? "（已卖一半）" : "").append("</td><td>")
                        .append(esc(p.get("path"))).append("</td><td>").append(esc(p.get("openedDay"))).append("</td><td>")
                        .append(esc(com.phoenixscout.report.RiskReward.price(((Number) p.get("entryPrice")).doubleValue()))).append("</td><td>")
                        .append(esc(p.get("amountUsd"))).append("</td><td>").append(esc(p.get("riskUsd"))).append("</td><td>")
                        .append(esc(com.phoenixscout.report.RiskReward.price(((Number) p.get("price")).doubleValue()))).append("<br><span class=muted>")
                        .append(esc(p.get("priceSource"))).append("</span></td><td class=").append(pnl >= 0 ? "ok" : "bad").append(">")
                        .append(money(pnl)).append("（").append(esc(p.get("pnlPct"))).append("%）</td><td>").append(esc(p.get("rMultiple"))).append("</td><td>")
                        .append(p.get("tp1") == null ? "无法估值" : esc(com.phoenixscout.report.RiskReward.price(((Number) p.get("tp1")).doubleValue())) + "（" + esc(p.get("toTp1Pct")) + "%）").append("</td><td>")
                        .append(esc(com.phoenixscout.report.RiskReward.price(((Number) p.get("stopLoss")).doubleValue()))).append("（").append(esc(p.get("toStopPct"))).append("%）</td><td>")
                        .append(esc(p.get("grade"))).append(" / ").append(esc(p.get("quality"))).append("/5 / ")
                        .append(p.get("entryEv") == null ? "样本不足" : esc(p.get("entryEv")) + "%").append("</td><td>").append(esc(p.get("reason"))).append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("<h2>最近平仓</h2><table><tr><th>账本</th><th>币</th><th>入场日</th><th>平仓日</th><th>入场价</th><th>平仓价</th><th>金额</th><th>原因</th><th>盈亏</th><th>R</th><th>入场期望值</th></tr>");
        for (Object o : (List<?>) sim.get("recentClosed")) {
            Map<?, ?> p = (Map<?, ?>) o;
            h.append("<tr><td>").append("track".equals(p.get("book")) ? "选币台账" : "模拟组合").append("</td><td>").append(esc(p.get("name"))).append("</td><td>")
                    .append(esc(p.get("opened_day"))).append("</td><td>").append(esc(p.get("closed_day"))).append("</td><td>").append(esc(p.get("entry_price")))
                    .append("</td><td>").append(esc(p.get("exit_price"))).append("</td><td>").append(esc(p.get("amount_usd"))).append("</td><td>")
                    .append(esc(p.get("exit_reason"))).append("</td><td>").append(money(p.get("pnl_usd"))).append("（").append(esc(p.get("pnl_pct")))
                    .append("%）</td><td>").append(esc(p.get("r_multiple"))).append("</td><td>").append(p.get("ev_pct") == null ? "—" : esc(p.get("ev_pct")) + "%").append("</td></tr>");
        }
        h.append("</table><p class=muted>页面每 60 秒刷新；实时价 2 分钟缓存。JSON 接口：<a href=/api/sim>/api/sim</a>（模拟仓）、<a href=/api/health>/api/health</a>（服务）。完整日报在 data\\reports，模拟仓明细在 data\\sim\\ledger.csv。</p></body></html>");
        return h.toString();
    }
}
