package com.phoenixscout.scan;

import com.phoenixscout.research.ScenarioCalculator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径 B 候选的初步情景推算（第十八条之二的简化版，研究层核实前使用）。
 * <ul>
 *   <li>估值倍数：同类协议（DefiLlama 同一 category，至少 8 个，不足则用全部协议）流通市值/年化收入的 p25/中位数/p75；
 *       只用年化收入 ≥ 100 万美元、倍数在 0.5–300 之间的样本，去掉收入极小造成的离谱倍数。</li>
 *   <li>倍数从当前值起 36 个月线性趋向目标（见 ScenarioCalculator），不假设一夜重估。</li>
 *   <li>年化收入：最近一个完整季度（91 天）收入 × 365/91。</li>
 *   <li>收入增速：保守 0；基准 = 最近两季度年化增速的一半，封顶 50%；乐观 = 最近两季度年化增速，封顶 100%。</li>
 *   <li>公链（Chain）类不做：公链估值不按手续费收入定价，倍数没有参考意义。</li>
 *   <li>流通量按当前值，未计解锁稀释——结果偏乐观，必须写明。</li>
 * </ul>
 * 只是“值不值得研究”的参考，不是预测或承诺，不进入 A 级判定（A 级用研究层核实过的输入重新计算）。
 */
public final class PrelimValuation {

    private PrelimValuation() {
    }

    public record Peer(String category, double marketCap, double annualRevenue) {
        double multiple() {
            return marketCap / annualRevenue;
        }
    }

    public record Result(String coinId, String peerGroup, int peerCount, double annualRevenue, double marketCap,
                         double currentMultiple, Map<String, Double> peerMultiples, Map<String, Double> growth,
                         List<ScenarioCalculator.Scenario> scenarios) { }

    static final int MIN_PEERS = 8;
    static final double MIN_PEER_REVENUE = 1_000_000;
    static final double MIN_MULT = 0.5, MAX_MULT = 300;

    public static Result compute(String coinId, String category, PathB.Result b, double price, double circulating,
                                 double marketCap, List<Peer> peers) {
        if (b.q0() == null || b.q1() == null || b.q2() == null || b.q0() <= 0 || price <= 0 || circulating <= 0) return null;
        if ("Chain".equalsIgnoreCase(category)) return null;
        List<Double> same = new ArrayList<>(), all = new ArrayList<>();
        for (Peer p : peers) {
            if (p.marketCap() <= 0 || p.annualRevenue() < MIN_PEER_REVENUE) continue;
            if (p.multiple() < MIN_MULT || p.multiple() > MAX_MULT) continue;
            all.add(p.multiple());
            if (category != null && category.equals(p.category())) same.add(p.multiple());
        }
        boolean useSame = same.size() >= MIN_PEERS;
        List<Double> use = useSame ? same : all;
        if (use.size() < MIN_PEERS) return null;
        use.sort(Double::compare);
        Map<String, Double> mult = new HashMap<>();
        mult.put("p25", quantile(use, 0.25));
        mult.put("median", quantile(use, 0.5));
        mult.put("p75", quantile(use, 0.75));

        double annual = b.q0() * 365.0 / 91.0;
        double twoQuarter = Math.pow(b.q0() / b.q2(), 2) - 1;          // 两个季度增速年化
        double recent = Math.max(0, twoQuarter);
        Map<String, Double> g = new HashMap<>();
        g.put("conservative", 0.0);
        g.put("base", Math.min(0.5, recent * 0.5));
        g.put("optimistic", Math.min(1.0, recent));
        var in = new ScenarioCalculator.Inputs(price, circulating, null, Map.of(), annual, g, mult);
        return new Result(coinId, useSame ? category : "全部协议", use.size(), annual, marketCap, marketCap / annual,
                mult, g, ScenarioCalculator.scenarios(in));
    }

    static double quantile(List<Double> sorted, double q) {
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }
}
