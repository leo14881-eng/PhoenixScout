package com.phoenixscout.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 台账复盘统计（每周日推送、状态页和 /api/sim 也用）。
 * 选币台账看“准不准”：第一目标命中率、止损率、平均 R、入场后 30/90/180/365 天跑赢 BTC 的比例和相对山寨中位数的超额。
 * 模拟组合看“稳不稳”：权益、收益率、最大回撤。已平仓少于 30 笔时注明样本不足。
 */
public class SimReview {

    public static final int MIN_SAMPLE = 30;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public SimReview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> stats(String book) {
        List<Map<String, Object>> all = jdbc.queryForList("SELECT * FROM sim_trade WHERE book=?", book);
        int total = all.size(), closed = 0, tp1 = 0, stopped = 0, wins = 0;
        double sumR = 0;
        Map<String, double[]> byPath = new LinkedHashMap<>();   // path -> [n, sumR]
        Map<Integer, List<double[]>> hz = new LinkedHashMap<>();
        for (int n : SimLedger.HORIZONS) hz.put(n, new ArrayList<>());
        Map<String, List<double[]>> byQ = new LinkedHashMap<>();   // 质量分 ≥4 / <4 / 未研究：入场后 90 天 [币, BTC]
        byQ.put("质量分≥4", new ArrayList<>());
        byQ.put("质量分<4", new ArrayList<>());
        for (var t : all) {
            if (((Number) t.get("tp1_done")).intValue() == 1) tp1++;
            if ("closed".equals(t.get("status"))) {
                closed++;
                double r = t.get("r_multiple") == null ? 0 : ((Number) t.get("r_multiple")).doubleValue();
                sumR += r;
                if (((Number) t.get("pnl_usd")).doubleValue() > 0) wins++;
                String reason = (String) t.get("exit_reason");
                if ("止损".equals(reason)) stopped++;
                double[] p = byPath.computeIfAbsent((String) t.get("path"), k -> new double[2]);
                p[0]++;
                p[1] += r;
            }
            try {
                JsonNode h = om.readTree((String) t.get("horizons"));
                for (int n : SimLedger.HORIZONS) {
                    JsonNode o = h.get(String.valueOf(n));
                    if (o == null || !o.get("coin").isNumber()) continue;
                    double coin = o.get("coin").asDouble();
                    double btc = o.path("btc").isNumber() ? o.get("btc").asDouble() : Double.NaN;
                    double alt = o.path("alt").isNumber() ? o.get("alt").asDouble() : Double.NaN;
                    hz.get(n).add(new double[]{coin, btc, alt});
                    if (n == 90) {
                        int q = t.get("quality") == null ? -1 : ((Number) t.get("quality")).intValue();
                        byQ.get(q >= 4 ? "质量分≥4" : "质量分<4").add(new double[]{coin, btc});
                    }
                }
            } catch (Exception ignored) {
                // 跳过损坏记录
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trades", total);
        m.put("closed", closed);
        m.put("tp1HitPct", total == 0 ? null : r1(tp1 * 100.0 / total));
        m.put("stopPctOfClosed", closed == 0 ? null : r1(stopped * 100.0 / closed));
        m.put("winPctOfClosed", closed == 0 ? null : r1(wins * 100.0 / closed));
        m.put("avgR", closed == 0 ? null : r2(sumR / closed));
        Map<String, Object> paths = new LinkedHashMap<>();
        byPath.forEach((k, v) -> paths.put(k, Map.of("closed", (int) v[0], "avgR", r2(v[1] / v[0]))));
        m.put("byPath", paths);
        Map<String, Object> hm = new LinkedHashMap<>();
        hz.forEach((n, list) -> {
            if (list.isEmpty()) return;
            double avg = list.stream().mapToDouble(x -> x[0]).average().orElse(0);
            long beatBtc = list.stream().filter(x -> !Double.isNaN(x[1]) && x[0] > x[1]).count();
            long withBtc = list.stream().filter(x -> !Double.isNaN(x[1])).count();
            double excessAlt = list.stream().filter(x -> !Double.isNaN(x[2])).mapToDouble(x -> x[0] - x[2]).average().orElse(Double.NaN);
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("n", list.size());
            o.put("avgReturnPct", r1(avg * 100));
            o.put("beatBtcPct", withBtc == 0 ? null : r1(beatBtc * 100.0 / withBtc));
            o.put("excessVsAltPct", Double.isNaN(excessAlt) ? null : r1(excessAlt * 100));
            hm.put(n + "天", o);
        });
        m.put("horizons", hm);
        Map<String, Object> qm = new LinkedHashMap<>();
        byQ.forEach((k, list) -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("n", list.size());
            o.put("avgReturn90Pct", list.isEmpty() ? null : r1(list.stream().mapToDouble(x -> x[0]).average().orElse(0) * 100));
            long withBtc = list.stream().filter(x -> !Double.isNaN(x[1])).count();
            o.put("beatBtcPct", withBtc == 0 ? null : r1(list.stream().filter(x -> !Double.isNaN(x[1]) && x[0] > x[1]).count() * 100.0 / withBtc));
            qm.put(k, o);
        });
        m.put("byQuality90d", qm);
        // 权益曲线最大回撤
        double peak = 0, mdd = 0;
        for (Double e : jdbc.queryForList("SELECT equity FROM sim_equity WHERE book=? ORDER BY day", Double.class, book)) {
            peak = Math.max(peak, e);
            if (peak > 0) mdd = Math.min(mdd, e / peak - 1);
        }
        m.put("maxDrawdownPct", r1(mdd * 100));
        m.put("sampleEnough", closed >= MIN_SAMPLE);
        return m;
    }

    @SuppressWarnings("unchecked")
    public String weeklyText(SimLedger ledger) {
        StringBuilder s = new StringBuilder("📊 模拟台账周复盘（只记账，不下真实订单）\n");
        for (String book : List.of(SimLedger.TRACK, SimLedger.PORTFOLIO)) {
            var st = stats(book);
            var sm = ledger.summary(book);
            s.append("\n").append(book.equals(SimLedger.TRACK) ? "【选币台账：选得准不准】" : "【模拟组合：照着投能不能稳定赚】").append("\n");
            s.append(String.format(Locale.ROOT, "记录 %s 笔，已平仓 %s 笔；估值目标命中 %s%%，止损 %s%%，平均 %sR",
                    st.get("trades"), st.get("closed"), nz(st.get("tp1HitPct")), nz(st.get("stopPctOfClosed")), nz(st.get("avgR"))));
            if (book.equals(SimLedger.PORTFOLIO)) {
                s.append(String.format(Locale.ROOT, "\n权益 %.0f 美元（%+.1f%%），最大回撤 %s%%，持仓 %d 个",
                        sm.equity() + sm.unrealized(), (sm.equity() + sm.unrealized()) / SimLedger.START_EQUITY * 100 - 100,
                        st.get("maxDrawdownPct"), sm.open()));
            }
            var hm = (Map<String, Map<String, Object>>) st.get("horizons");
            hm.forEach((k, o) -> s.append(String.format(Locale.ROOT, "\n入场后%s：%s 笔，平均 %s%%，跑赢 BTC %s%%，相对山寨中位数 %s%%",
                    k, o.get("n"), o.get("avgReturnPct"), nz(o.get("beatBtcPct")), nz(o.get("excessVsAltPct")))));
            if (book.equals(SimLedger.TRACK)) {
                var qm = (Map<String, Map<String, Object>>) st.get("byQuality90d");
                qm.forEach((k, o) -> s.append(String.format(Locale.ROOT, "\n%s：入场后 90 天 %s 笔，平均 %s%%，跑赢 BTC %s%%",
                        k, o.get("n"), nz(o.get("avgReturn90Pct")), nz(o.get("beatBtcPct")))));
            }
            if (!Boolean.TRUE.equals(st.get("sampleEnough"))) s.append("\n（已平仓不足 30 笔，结论不可靠）");
            s.append("\n");
        }
        return s.toString();
    }

    static String nz(Object o) {
        return o == null ? "—" : o.toString();
    }

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double r2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
