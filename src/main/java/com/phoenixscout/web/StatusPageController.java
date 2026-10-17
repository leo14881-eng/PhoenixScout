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
    private final Instant started = Instant.now();

    public StatusPageController(DailyScanService scan, ScanScheduler scheduler, JdbcTemplate jdbc, CoinGeckoClient cg, Path dataDir) {
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
        List<Map<String, Object>> pos = jdbc.queryForList("SELECT * FROM sim_position ORDER BY status DESC, id DESC LIMIT 60");
        Map<String, Object> sum = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(pnl_usd),0) s, COALESCE(SUM(CASE WHEN pnl_usd>0 THEN 1 ELSE 0 END),0) w"
                + " FROM sim_position WHERE status='closed'");
        double unreal = 0;
        int open = 0;
        for (var p : pos) {
            if (!"open".equals(p.get("status"))) continue;
            open++;
            unreal += ((Number) p.get("qty")).doubleValue() * ((Number) p.get("last_price")).doubleValue() * 0.997 - 1000;
        }
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

        h.append(String.format(Locale.ROOT, "<h2>模拟仓（只记账）：持仓 %d 个，浮动 %+.0f 美元｜已平仓 %s 笔，盈 %s，已实现 %s 美元</h2>",
                open, unreal, sum.get("n"), sum.get("w"), money(sum.get("s"))));
        h.append("<table><tr><th>币</th><th>路径</th><th>入场日</th><th>入场价</th><th>金额</th><th>目标</th><th>防守</th><th>最新价</th><th>状态</th><th>盈亏</th><th>入场原因</th></tr>");
        for (var p : pos) {
            boolean isOpen = "open".equals(p.get("status"));
            Object pnl = isOpen ? ((Number) p.get("qty")).doubleValue() * ((Number) p.get("last_price")).doubleValue() * 0.997 - 1000 : p.get("pnl_usd");
            h.append("<tr><td>").append(esc(p.get("name"))).append("</td><td>").append(esc(p.get("path"))).append("</td><td>")
                    .append(esc(p.get("opened_day"))).append("</td><td>").append(esc(p.get("entry_price"))).append("</td><td>")
                    .append(esc(p.get("amount_usd"))).append("</td><td>").append(esc(p.get("take_profit"))).append("</td><td>")
                    .append(esc(p.get("stop_loss"))).append("</td><td>").append(esc(p.get("last_price"))).append("</td><td>")
                    .append(isOpen ? "持有" : esc(p.get("exit_reason")) + " " + esc(p.get("closed_day"))).append("</td><td>")
                    .append(money(pnl)).append("</td><td>").append(esc(p.get("reason"))).append("</td></tr>");
        }
        h.append("</table><p class=muted>数据每 60 秒刷新。完整日报在 data\\reports，模拟仓明细在 data\\sim\\ledger.csv。</p></body></html>");
        return h.toString();
    }
}
