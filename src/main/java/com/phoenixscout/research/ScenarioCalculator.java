package com.phoenixscout.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 第十八条（5–20 倍估值）与第十八条之二（现在买入的预期收益与时间）的计算。纯函数。
 * 口径：估值倍数 = 流通市值 / 年化收入；未来价格 = 预期市值 / 届时流通量（计入解锁稀释与销毁）。
 * 估值倍数不会一夜重估：从当前倍数 m0 起，36 个月内线性趋向情景目标倍数——
 * 保守 = min(m0, 同类 p25)（只可能压缩）；基准 = 同类中位数；乐观 = max(m0, 同类 p75)。
 * 结果是情景推算，不是预测或承诺；不包含牛熊周期等整体行情因素。
 */
public final class ScenarioCalculator {

    private ScenarioCalculator() {
    }

    public record Inputs(double price, double circulating, Double totalSupply, Map<Integer, Double> supplyByMonth,
                         double annualRevenue, Map<String, Double> growth, Map<String, Double> multiples) { }

    /** 一个情景：增速、估值倍数、各时点的预期收益倍数、达到 3/5/10 倍所需月数（36 个月内达不到为 null）。 */
    public record Scenario(String name, String label, double growth, double multiple,
                           Map<Integer, Double> multipleAt, Map<Integer, Integer> monthsTo) { }

    /** 第十八条：达到 k 倍时需要的市值、FDV 与业务规模。 */
    public record Target(int times, double targetPrice, double targetCircMcap, Double targetFdv, Double requiredRevenue) { }

    static final int[] HORIZONS = {6, 12, 24, 36};
    static final int[] GOALS = {3, 5, 10};
    public static final int[] TARGETS = {3, 5, 10, 15, 20};

    public static List<Scenario> scenarios(Inputs in) {
        List<Scenario> out = new ArrayList<>();
        String[][] defs = {{"conservative", "保守", "p25"}, {"base", "基准", "median"}, {"optimistic", "乐观", "p75"}};
        for (String[] d : defs) {
            Double g = in.growth().get(d[0]);
            Double peer = in.multiples().get(d[2]);
            if (g == null || peer == null) continue;
            double m0 = currentMultiple(in);
            double m = switch (d[0]) {
                case "conservative" -> Math.min(m0, peer);
                case "optimistic" -> Math.max(m0, peer);
                default -> peer;
            };
            Map<Integer, Double> at = new LinkedHashMap<>();
            for (int h : HORIZONS) at.put(h, priceMultiple(in, g, m, h));
            Map<Integer, Integer> to = new LinkedHashMap<>();
            for (int goal : GOALS) {
                Integer month = null;
                for (int t = 1; t <= 36; t++) {
                    if (priceMultiple(in, g, m, t) >= goal) {
                        month = t;
                        break;
                    }
                }
                to.put(goal, month);
            }
            out.add(new Scenario(d[0], d[1], g, m, at, to));
        }
        return out;
    }

    /** 当前流通市值 / 年化收入。 */
    public static double currentMultiple(Inputs in) {
        return in.price() * in.circulating() / in.annualRevenue();
    }

    static double priceMultiple(Inputs in, double growth, double targetMultiple, int month) {
        double revenue = in.annualRevenue() * Math.pow(1 + growth, month / 12.0);
        double m0 = currentMultiple(in);
        double multiple = m0 + (targetMultiple - m0) * Math.min(month, 36) / 36.0;
        double mcap = revenue * multiple;
        double price = mcap / supplyAt(in, month);
        return price / in.price();
    }

    /** 流通量：0 个月 = 当前流通量，12/24/36 个月取研究给出的值，中间线性插值；缺失的点沿用前一个已知值。 */
    static double supplyAt(Inputs in, int month) {
        TreeMap<Integer, Double> pts = new TreeMap<>();
        pts.put(0, in.circulating());
        if (in.supplyByMonth() != null) in.supplyByMonth().forEach((k, v) -> { if (v != null && v > 0) pts.put(k, v); });
        var lo = pts.floorEntry(month);
        var hi = pts.ceilingEntry(month);
        if (hi == null || lo.getKey().equals(hi.getKey())) return lo.getValue();
        double f = (month - lo.getKey()) / (double) (hi.getKey() - lo.getKey());
        return lo.getValue() + (hi.getValue() - lo.getValue()) * f;
    }

    /** 第十八条：按 36 个月后的流通量与总量计算 3/5/10/15/20 倍所需规模（业务规模用同赛道中位数倍数倒推）。 */
    public static List<Target> targets(Inputs in) {
        List<Target> out = new ArrayList<>();
        double supply36 = supplyAt(in, 36);
        Double median = in.multiples().get("median");
        for (int k : TARGETS) {
            double p = in.price() * k;
            double mcap = p * supply36;
            Double fdv = in.totalSupply() == null ? null : p * Math.max(in.totalSupply(), supply36);
            Double rev = median == null || median <= 0 ? null : mcap / median;
            out.add(new Target(k, p, mcap, fdv, rev));
        }
        return out;
    }

    /** 第十四条第 7 项：基准或乐观情景在 36 个月内能达到 5 倍。 */
    /** “合理的 5 倍估值情景”（第十四条第 7 项，V2.6.5 收紧）：只看基准情景，36 个月内达到 5 倍；乐观情景只作参考，不能单独让这一项通过。 */
    public static boolean fiveXPlausible(List<Scenario> s) {
        return s.stream().filter(x -> x.name().equals("base")).anyMatch(x -> x.monthsTo().get(5) != null);
    }
}
