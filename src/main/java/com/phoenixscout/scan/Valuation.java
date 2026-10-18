package com.phoenixscout.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * 估值目标（V2.6 第二条，取代“回到 2025-10-31 价格”）。
 * <ul>
 *   <li>有收入的项目：目标市值 = 年化协议收入（protocol revenue，DefiLlama dailyRevenue 最近 30 天 × 365/30，不是总手续费）
 *       × 同赛道“流通市值/年化收入”中位数；同赛道 = DefiLlama 同一 category、年化收入 ≥100 万美元、倍数 0.5–300，
 *       至少 5 个，不足用全部协议（至少 8 个）并注明。</li>
 *   <li>目标价 = 目标市值 ÷ 未来 12 个月流通量：研究层给出未来 12 个月解锁时用“流通量 ×（1 + 解锁比例）”；
 *       没有解锁数据时按总量（假设全部解锁，最保守），总量缺失用最大供应量；都没有 → 无法估值。</li>
 *   <li>没有可核实收入、公链类、同类样本不足 → 不输出目标价，标“无法估值”（最高 B 级）。</li>
 * </ul>
 * 历史价格只作参考显示，不作目标。
 */
public final class Valuation {

    private Valuation() {
    }

    public record Peer(String category, double marketCap, double annualRevenue) {
        double multiple() {
            return marketCap / annualRevenue;
        }
    }

    public record PeerStats(String group, int count, double median) { }

    /** 同赛道（DefiLlama category）至少 5 个可比项目才用同赛道中位数，否则退回全部协议并在报告里注明。 */
    public static final int MIN_SAME_CATEGORY = 5;

    public record Target(Double targetPrice, Double targetMcap, Double upside, String method, String supplyBasis, String reason) {
        public boolean available() {
            return targetPrice != null;
        }

        public static Target none(String reason) {
            return new Target(null, null, null, null, null, reason);
        }
    }

    public static PeerStats peers(String category, List<Peer> peers) {
        List<Double> same = new ArrayList<>(), all = new ArrayList<>();
        for (Peer p : peers) {
            if (p.marketCap() <= 0 || p.annualRevenue() < PrelimValuation.MIN_PEER_REVENUE) continue;
            if (p.multiple() < PrelimValuation.MIN_MULT || p.multiple() > PrelimValuation.MAX_MULT) continue;
            all.add(p.multiple());
            if (category != null && category.equals(p.category())) same.add(p.multiple());
        }
        boolean useSame = same.size() >= MIN_SAME_CATEGORY;
        List<Double> use = useSame ? same : all;
        if (!useSame && use.size() < PrelimValuation.MIN_PEERS) return null;
        use.sort(Double::compare);
        return new PeerStats(useSame ? "同赛道 " + category : "全部协议（同赛道 " + (category == null ? "未知" : category) + " 只有 "
                + same.size() + " 个，不足 5 个）", use.size(), PrelimValuation.quantile(use, 0.5));
    }

    /**
     * @param supply12m 研究层核实的未来 12 个月流通量（可为 null）
     */
    public static Target target(Double price, Double annualRevenue, String category, PeerStats peers, Double circulating,
                                Double supply12m, Double totalSupply, Double maxSupply) {
        if (price == null || price <= 0) return Target.none("缺少现价");
        if (annualRevenue == null || annualRevenue <= 0) return Target.none("无可核实收入，无法估值");
        if ("Chain".equalsIgnoreCase(category)) return Target.none("公链不按协议收入估值，无法估值");
        if (peers == null) return Target.none("同类可比样本不足 8 个，无法估值");
        double mcap = annualRevenue * peers.median();
        Double supply;
        String basis;
        if (supply12m != null && supply12m > 0) {
            supply = supply12m;
            basis = "研究核实的未来 12 个月流通量";
        } else if (totalSupply != null && totalSupply > 0) {
            supply = totalSupply;
            basis = "总量（未找到解锁计划，假设全部解锁，保守）";
        } else if (maxSupply != null && maxSupply > 0) {
            supply = maxSupply;
            basis = "最大供应量（未找到解锁计划，保守）";
        } else {
            return Target.none("缺少供应量数据，无法估值");
        }
        double tp = mcap / supply;
        String method = String.format(java.util.Locale.ROOT, "年化协议收入 %s × 中位数 %.1f 倍（%s，%d 个）",
                com.phoenixscout.report.ReportWriter.usd(annualRevenue), peers.median(), peers.group(), peers.count());
        return new Target(tp, mcap, tp / price - 1, method, basis, null);
    }
}
