package com.phoenixscout.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.notify.TelegramNotifier;
import com.phoenixscout.report.ReportWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 读取研究层写出的 data/research/*.json，按第十四条逐条校验评级，计算情景，推送新 A 级与降级（第二十四条）。
 * 每个文件按“文件名+大小”只处理一次；每天（UTC）最多推送 3 个 A 级。
 */
public class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);
    static final int MAX_A_PER_DAY = 3;

    private final Path dir;
    private final JdbcTemplate jdbc;
    private final TelegramNotifier tg;
    private final Clock clock;
    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

    public ResearchService(Path researchDir, JdbcTemplate jdbc, TelegramNotifier tg, Clock clock) {
        this.dir = researchDir;
        this.jdbc = jdbc;
        this.tg = tg;
        this.clock = clock;
    }

    public synchronized int ingest() {
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                if (process(f)) n++;
            }
        } catch (IOException e) {
            log.warn("读取研究目录失败：{}", e.getMessage());
        }
        return n;
    }

    boolean process(Path f) {
        String marker;
        try {
            marker = "research:" + f.getFileName() + ":" + Files.size(f);
        } catch (IOException e) {
            return false;
        }
        if (!jdbc.queryForList("SELECT v FROM kv WHERE k = ?", String.class, marker).isEmpty()) return false;
        try {
            JsonNode r = om.readTree(f.toFile());
            String coin = r.path("coin_id").asText("");
            if (coin.isBlank()) throw new IOException("缺少 coin_id");
            jdbc.update("INSERT OR REPLACE INTO research_raw(coin_id, research_file, researched_at, json) VALUES (?,?,?,?)",
                    coin, f.getFileName().toString(), r.path("researched_at").asText(Instant.now(clock).toString()), om.writeValueAsString(r));
            Map<String, Object> mkt = market(coin);
            Double price = mkt == null ? null : (Double) mkt.get("price");
            String path = candidatePath(coin);
            ChecklistEvaluator.Result res = ChecklistEvaluator.evaluate(r, path, price);
            String prev = previousGrade(coin);
            jdbc.update("INSERT INTO research_grade(coin_id, research_file, graded_at, grade, failed_items, detail) VALUES (?,?,?,?,?,?)",
                    coin, f.getFileName().toString(), Instant.now(clock).toString(), res.grade(),
                    om.writeValueAsString(res.failed()), om.writeValueAsString(res));
            saveEvidence(coin, res);
            if ("A".equals(res.grade())) {
                if (aSentToday() < MAX_A_PER_DAY && !"A".equals(prev)) {
                    send("A", coin, aMessage(coin, r, res, mkt, path));
                } else if ("A".equals(prev) && persistentA(coin)) {
                    log.info("{} 持续 A 级", coin);
                }
            } else if ("A".equals(prev)) {
                send("downgrade", coin, downgradeMessage(coin, r, res, mkt));
            }
            jdbc.update("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", marker, Instant.now(clock).toString());
            log.info("研究结果 {}：{}（未通过 {}）", coin, res.grade(), res.failed());
            return true;
        } catch (Exception e) {
            log.warn("研究文件 {} 无法处理：{}", f.getFileName(), e.getMessage());
            jdbc.update("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", marker, "invalid " + Instant.now(clock));
            tg.send("⚠️ 研究文件格式不对，已跳过：" + f.getFileName() + "（" + e.getMessage() + "）");
            return false;
        }
    }

    /** 研究结果有效期（V2.6 质量分、解锁数据只用 30 天内的研究）。 */
    public static final int RAW_VALID_DAYS = 30;

    /** 每个币最新一份 30 天内的研究原文。 */
    public static Map<String, JsonNode> latestRaw(JdbcTemplate jdbc, java.util.Collection<String> coins, Instant now) {
        Map<String, JsonNode> out = new java.util.HashMap<>();
        if (coins.isEmpty()) return out;
        ObjectMapper om = new ObjectMapper();
        String since = now.minus(RAW_VALID_DAYS, ChronoUnit.DAYS).toString();
        for (var row : jdbc.queryForList("SELECT coin_id, json FROM research_raw WHERE researched_at >= ? ORDER BY researched_at", since)) {
            String c = (String) row.get("coin_id");
            if (!coins.contains(c)) continue;
            try {
                out.put(c, om.readTree((String) row.get("json")));
            } catch (IOException ignored) {
                // 损坏的记录跳过
            }
        }
        return out;
    }

    private void send(String kind, String coin, String text) {
        boolean ok = tg.send(text);
        jdbc.update("INSERT INTO notification(kind, coin_id, scan_day, sent_at, ok) VALUES (?,?,?,?,?)",
                kind, coin, LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString(), Instant.now(clock).toString(), ok ? 1 : 0);
    }

    int aSentToday() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE kind = 'A' AND scan_day = ?", Integer.class,
                LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString());
        return n == null ? 0 : n;
    }

    String previousGrade(String coin) {
        List<String> g = jdbc.queryForList("SELECT grade FROM research_grade WHERE coin_id = ? ORDER BY id DESC LIMIT 1", String.class, coin);
        return g.isEmpty() ? null : g.get(0);
    }

    /** 14 天内至少两次 A，且首末相隔 ≥ 7 天。 */
    boolean persistentA(String coin) {
        String since = Instant.now(clock).minus(14, ChronoUnit.DAYS).toString();
        List<String> at = jdbc.queryForList("SELECT graded_at FROM research_grade WHERE coin_id = ? AND grade = 'A' AND graded_at >= ? ORDER BY graded_at",
                String.class, coin, since);
        return at.size() >= 2 && ChronoUnit.DAYS.between(Instant.parse(at.get(0)), Instant.parse(at.get(at.size() - 1))) >= 7;
    }

    String candidatePath(String coin) {
        List<String> p = jdbc.queryForList("SELECT path FROM candidate_state WHERE coin_id = ? AND path IN ('A','B','C') ORDER BY scan_day DESC LIMIT 1",
                String.class, coin);
        return p.isEmpty() ? "A" : p.get(0);
    }

    Map<String, Object> market(String coin) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name, symbol, price, market_cap, fdv, scan_day FROM market_snapshot WHERE coin_id = ? ORDER BY scan_day DESC LIMIT 1", coin);
        if (rows.isEmpty()) return null;
        Map<String, Object> m = new java.util.HashMap<>(rows.get(0));
        m.replaceAll((k, v) -> v instanceof Number num && !k.equals("scan_day") ? num.doubleValue() : v);
        return m;
    }

    private JsonNode pathADetail(String coin) {
        List<String> d = jdbc.queryForList("SELECT detail FROM candidate_state WHERE coin_id = ? AND path = 'A' ORDER BY scan_day DESC LIMIT 1",
                String.class, coin);
        try {
            return d.isEmpty() ? null : om.readTree(d.get(0));
        } catch (IOException e) {
            return null;
        }
    }

    private void saveEvidence(String coin, ChecklistEvaluator.Result res) {
        String now = Instant.now(clock).toString();
        List<Object[]> rows = new ArrayList<>();
        for (var it : res.items()) {
            for (var s : it.sources()) {
                rows.add(new Object[]{coin, it.key(), it.claim() == null ? "" : it.claim(), s.url(), s.date(), it.pass() ? 1 : 0, now});
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO research_evidence(coin_id, item, claim, url, source_date, verified, created_at) VALUES (?,?,?,?,?,?,?)", rows);
        }
    }

    static String x(Double m) {
        return m == null ? "—" : String.format(Locale.ROOT, "%.1fx", m);
    }

    String aMessage(String coin, JsonNode r, ChecklistEvaluator.Result res, Map<String, Object> mkt, String path) {
        StringBuilder s = new StringBuilder();
        String name = mkt == null ? coin : mkt.get("name") + "（" + String.valueOf(mkt.get("symbol")).toUpperCase(Locale.ROOT) + "）";
        s.append("🚀 PhoenixScout 新 A 级：").append(name).append("\n");
        s.append("https://www.coingecko.com/en/coins/").append(coin).append("\n");
        if (mkt != null) {
            s.append("价格 $").append(String.format(Locale.ROOT, "%.6g", (Double) mkt.get("price")))
                    .append("｜流通市值 ").append(ReportWriter.usd((Double) mkt.get("market_cap")))
                    .append("｜FDV ").append(ReportWriter.usd((Double) mkt.get("fdv")))
                    .append("（行情 ").append(mkt.get("scan_day")).append(" UTC）\n");
        }
        JsonNode sg = r.path("items").path("supply_growth").get("supply_growth_12m_pct");
        if (sg != null && sg.isNumber()) s.append("未来12个月供应变化 ").append(String.format(Locale.ROOT, "%+.1f%%", sg.asDouble())).append("\n");
        s.append("入选路径：").append(switch (path) { case "B" -> "B 基本面领先"; case "C" -> "C 早期高成长"; default -> "A 超跌转强"; });
        JsonNode pa = pathADetail(coin);
        if (pa != null) {
            s.append("｜长期 ").append(ReportWriter.pct(num(pa.get("rLong")))).append("｜30天 ").append(ReportWriter.pct(num(pa.get("r30"))))
                    .append("｜90天 ").append(ReportWriter.pct(num(pa.get("r90"))));
        }
        s.append("\n\n理由：").append(r.path("summary_reason").asText("—")).append("\n");
        for (var it : res.items()) {
            s.append("✓ ").append(it.label());
            if (it.claim() != null && !it.claim().isBlank()) s.append("：").append(it.claim());
            if (!it.sources().isEmpty()) s.append("（").append(it.sources().get(0).url()).append("，").append(it.sources().get(0).date()).append("）");
            else if (it.reason() != null) s.append("：").append(it.reason());
            s.append("\n");
        }
        JsonNode vc = r.path("items").path("value_capture");
        s.append("价值捕获机制：").append(vc.path("mechanism").asText("—")).append("\n");

        if (res.inputs() != null && !res.scenarios().isEmpty()) {
            s.append("\n现在买入：").append(com.phoenixscout.report.RiskReward.forScenarios(res.inputs().price(), res.scenarios()))
                    .append("（情景推算，不是预测或承诺）\n");
        }
        s.append("\n5/10/20倍所需（按36个月后流通量）：\n");
        for (var t : res.targets()) {
            if (t.times() != 5 && t.times() != 10 && t.times() != 20) continue;
            s.append(t.times()).append("倍：价格 $").append(String.format(Locale.ROOT, "%.4g", t.targetPrice()))
                    .append("，流通市值 ").append(ReportWriter.usd(t.targetCircMcap()))
                    .append("，FDV ").append(ReportWriter.usd(t.targetFdv()))
                    .append("，需年收入 ").append(ReportWriter.usd(t.requiredRevenue())).append("\n");
        }
        s.append("\n现在买入（情景推算，不是预测或承诺；不含牛熊周期）：\n");
        for (var sc : res.scenarios()) {
            s.append(sc.label()).append("（收入增速 ").append(String.format(Locale.ROOT, "%.0f%%", sc.growth() * 100))
                    .append("，估值 ").append(String.format(Locale.ROOT, "%.0f", sc.multiple())).append(" 倍收入）：");
            sc.multipleAt().forEach((m, v) -> s.append(m).append("月 ").append(x(v)).append(" "));
            s.append("｜");
            List<String> to = new ArrayList<>();
            sc.monthsTo().forEach((goal, months) -> to.add(goal + "倍" + (months == null ? "36个月内达不到" : "约" + months + "个月")));
            s.append(String.join("，", to)).append("\n");
        }
        s.append("情景失效的主要条件：收入下滑、解锁超预期、估值倍数收缩\n");
        appendList(s, "主要风险", r.path("risks"));
        appendList(s, "待验证", r.path("unverified"));
        res.notes().forEach(n -> s.append("注：").append(n).append("\n"));
        s.append("\n评级不等于买入建议，是否买入由你决定。");
        return s.toString();
    }

    String downgradeMessage(String coin, JsonNode r, ChecklistEvaluator.Result res, Map<String, Object> mkt) {
        StringBuilder s = new StringBuilder("⬇️ PhoenixScout 降级：");
        s.append(mkt == null ? coin : mkt.get("name")).append(" 由 A 级降为 ").append(res.grade()).append(" 级\n原因：\n");
        for (var it : res.items()) {
            if (!it.pass()) s.append("✗ ").append(it.label()).append("：").append(it.reason())
                    .append(it.claim() == null ? "" : "（" + it.claim() + "）").append("\n");
        }
        appendList(s, "主要风险", r.path("risks"));
        return s.toString();
    }

    private static void appendList(StringBuilder s, String title, JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return;
        List<String> v = new ArrayList<>();
        arr.forEach(a -> v.add(a.asText()));
        s.append(title).append("：").append(String.join("；", v)).append("\n");
    }

    private static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }
}
