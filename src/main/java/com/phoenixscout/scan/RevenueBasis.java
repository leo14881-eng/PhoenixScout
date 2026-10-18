package com.phoenixscout.scan;

import com.phoenixscout.llama.DefiLlamaClient;
import com.phoenixscout.report.ReportWriter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 收入口径（V2.6.2，用户审阅 2026-10-18 第三版日报后要求）。
 * <ul>
 *   <li>按项目合并：DefiLlama 同一母协议（parentProtocol）下的各链、各版本（如 GMX V1、GMX EVM、GMX Solana）合并成一个项目；
 *       同一代币对应的所有项目再合并。增长信号和估值用同一口径。</li>
 *   <li>保守年化：年化收入 = min(近30天×12, 近90天×4, 近12个月实际)，缺的项不参与；三个数都在报告里显示。
 *       近30天×12 超过近12个月实际的 2 倍时标注“近期收入异常偏高，可能不可持续”。</li>
 * </ul>
 * 都是协议收入（DefiLlama dailyRevenue），不是总手续费，也不是持币人利润。
 */
public final class RevenueBasis {

    private RevenueBasis() {
    }

    public record Annual(Double r30x12, Double r90x4, Double actual12m, Double used, boolean spike) {
        public String text() {
            return "近30天×12 " + ReportWriter.usd(r30x12) + "｜近90天×4 " + ReportWriter.usd(r90x4) + "｜近12个月实际 " + ReportWriter.usd(actual12m)
                    + " → 取最小 " + ReportWriter.usd(used) + (spike ? "（近期收入异常偏高，可能不可持续）" : "");
        }
    }

    public static Annual of(Double sum30, Double sum90, Double sum365) {
        Double a = sum30 == null ? null : sum30 * 12;
        Double b = sum90 == null ? null : sum90 * 4;
        Double c = sum365;
        Double used = null;
        for (Double v : new Double[]{a, b, c}) if (v != null && (used == null || v < used)) used = v;
        boolean spike = a != null && c != null && c > 0 && a > 2 * c;
        return new Annual(a, b, c, used, spike);
    }

    /** 从逐日收入算：最近 30/90/365 天合计（截至 latest）。不足 365 天数据时“近12个月实际”记为缺失，不拿短期合计冒充。 */
    public static Annual fromDaily(NavigableMap<LocalDate, Double> rev, LocalDate latest) {
        if (rev == null || rev.isEmpty()) return of(null, null, null);
        return of(sum(rev, latest, 30), sum(rev, latest, 90), rev.firstKey().isAfter(latest.minusDays(364)) ? null : sum(rev, latest, 365));
    }

    static Double sum(NavigableMap<LocalDate, Double> rev, LocalDate latest, int days) {
        var sub = rev.subMap(latest.minusDays(days - 1L), true, latest, true);
        if (sub.isEmpty()) return null;
        return sub.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** 项目 key：有母协议用母协议 slug（parent#gmx → gmx），否则用协议自己的 slug。 */
    public static String projectKey(DefiLlamaClient.ProtocolRevenue p) {
        if (p.parentProtocol() != null) return p.parentProtocol().startsWith("parent#") ? p.parentProtocol().substring(7) : p.parentProtocol();
        return p.slug();
    }

    /** 一个代币的全部收入（可能来自多个项目）。children：项目 key → 子协议 slug（母协议明细取不到时逐个子协议加总）。 */
    public record CoinRevenue(String coinId, Map<String, List<String>> children, double sum30, Double sum60to30, Double sum1y,
                              String category) {
        public List<String> projectKeys() {
            return new ArrayList<>(children.keySet());
        }
    }

    /** 按代币合并全部协议：30 天、前 30 天、近 1 年收入相加；赛道取 30 天收入最大的子协议的 category。 */
    public static Map<String, CoinRevenue> byCoin(List<DefiLlamaClient.ProtocolRevenue> protos) {
        record Acc(Map<String, List<String>> children, double[] s30, double[] s60, boolean[] has60, double[] s1y, boolean[] has1y,
                   String[] cat, double[] catRev) { }
        // 母协议下的子协议若对应不同代币，母协议合并明细会混入别的代币收入：这种母协议改为逐个子协议计算
        Map<String, java.util.Set<String>> geckosByParent = new java.util.HashMap<>();
        for (var p : protos) {
            if (p.parentProtocol() != null && p.geckoId() != null)
                geckosByParent.computeIfAbsent(p.parentProtocol(), k -> new java.util.HashSet<>()).add(p.geckoId());
        }
        Map<String, Acc> m = new LinkedHashMap<>();
        for (var p : protos) {
            if (p.geckoId() == null || p.total30d() == null || p.total30d() < 0) continue;
            boolean mixed = p.parentProtocol() != null && geckosByParent.getOrDefault(p.parentProtocol(), java.util.Set.of()).size() > 1;
            Acc a = m.computeIfAbsent(p.geckoId(), k -> new Acc(new LinkedHashMap<>(), new double[1], new double[1], new boolean[1],
                    new double[1], new boolean[1], new String[1], new double[]{-1}));
            a.children().computeIfAbsent(mixed ? p.slug() : projectKey(p), k -> new ArrayList<>()).add(p.slug());
            a.s30()[0] += p.total30d();
            if (p.total60dto30d() != null) { a.s60()[0] += p.total60dto30d(); a.has60()[0] = true; }
            if (p.total1y() != null) { a.s1y()[0] += p.total1y(); a.has1y()[0] = true; }
            if (p.total30d() > a.catRev()[0]) { a.catRev()[0] = p.total30d(); a.cat()[0] = p.category(); }
        }
        Map<String, CoinRevenue> out = new LinkedHashMap<>();
        m.forEach((id, a) -> out.put(id, new CoinRevenue(id, a.children(), a.s30()[0], a.has60()[0] ? a.s60()[0] : null,
                a.has1y()[0] ? a.s1y()[0] : null, a.cat()[0])));
        return out;
    }

    /** 多个项目的逐日收入相加（同一天相加；某项目当天没有数据就不计入该项目）。 */
    public static TreeMap<LocalDate, Double> merge(List<? extends NavigableMap<LocalDate, Double>> series) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        for (var s : series) s.forEach((d, v) -> out.merge(d, v, Double::sum));
        return out;
    }
}
