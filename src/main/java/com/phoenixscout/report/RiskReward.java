package com.phoenixscout.report;

import com.phoenixscout.research.ScenarioCalculator;

import java.util.List;
import java.util.Locale;

/**
 * 现价、未来收益与盈亏比的统一写法。
 * <ul>
 *   <li>有收入情景（路径 B 初步推算、A 级核实推算）：目标 = 基准情景 12/36 个月价格，乐观 36 个月作上限参考；
 *       风险 = 保守情景同期亏损；盈亏比 = 基准收益 ÷ 保守亏损。</li>
 *   <li>只有价格（路径 A）：目标 = 回到长期区间起点价（2025-10-31，或一年前价格）；风险 = 跌破近 30 天最低价；
 *       盈亏比 = 目标涨幅 ÷ 到防守位的跌幅。这是价格结构参考，不是估值。</li>
 * </ul>
 * 均为情景推算，不是预测或承诺。
 */
public final class RiskReward {

    private RiskReward() {
    }

    public static String price(double p) {
        if (p >= 1000) return String.format(Locale.ROOT, "$%,.0f", p);
        if (p >= 1) return String.format(Locale.ROOT, "$%.3f", p);
        return String.format(Locale.ROOT, "$%.4g", p);
    }

    static String pct(double r) {
        return String.format(Locale.ROOT, "%+.0f%%", r * 100);
    }

    /** 盈亏比文字：收益 ÷ 亏损。 */
    public static String ratio(double up, double down) {
        if (up <= 0) return "盈亏比 <0（目标情景不赚）";
        if (down <= 0.01) return "下行有限（保守情景不亏）";
        return String.format(Locale.ROOT, "盈亏比 %.1f", up / down);
    }

    public static String forScenarios(double price, List<ScenarioCalculator.Scenario> scenarios) {
        ScenarioCalculator.Scenario cons = null, base = null, opt = null;
        for (var s : scenarios) {
            switch (s.name()) {
                case "conservative" -> cons = s;
                case "base" -> base = s;
                case "optimistic" -> opt = s;
                default -> { }
            }
        }
        if (cons == null || base == null) return "现价 " + price(price) + "（情景不全，无法计算盈亏比）";
        double b12 = base.multipleAt().get(12), b36 = base.multipleAt().get(36);
        double c12 = cons.multipleAt().get(12), c36 = cons.multipleAt().get(36);
        StringBuilder s = new StringBuilder("现价 ").append(price(price))
                .append("｜基准目标 12月 ").append(price(price * b12)).append("（").append(pct(b12 - 1)).append("）")
                .append("、36月 ").append(price(price * b36)).append("（").append(pct(b36 - 1)).append("）");
        if (opt != null) {
            double o36 = opt.multipleAt().get(36);
            s.append("｜乐观 36月 ").append(price(price * o36)).append("（").append(pct(o36 - 1)).append("）");
        }
        s.append("｜保守 12月 ").append(pct(c12 - 1)).append("、36月 ").append(pct(c36 - 1))
                .append("｜12月").append(ratio(b12 - 1, 1 - c12)).append("，36月").append(ratio(b36 - 1, 1 - c36));
        return s.toString();
    }

    /** 路径 A：价格结构参考。base 为长期区间起点价（或一年前价格），low30 为近 30 天最低价。 */
    public static String forPriceStructure(Double price, Double base, String baseLabel, Double low30) {
        if (price == null) return "现价缺失";
        StringBuilder s = new StringBuilder("现价 ").append(price(price));
        Double up = base == null ? null : base / price - 1;
        Double down = low30 == null ? null : 1 - low30 / price;
        if (up != null) s.append("｜目标 回到").append(baseLabel).append(" ").append(price(base)).append("（").append(pct(up)).append("）");
        if (down != null) s.append("｜防守位 近30日低点 ").append(price(low30)).append("（").append(pct(-down)).append("）");
        if (up != null && down != null) s.append("｜").append(ratio(up, down));
        return s.toString();
    }
}
