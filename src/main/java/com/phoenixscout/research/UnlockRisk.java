package com.phoenixscout.research;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解锁风险标签（V2.6 第五条）。流通率由 CoinGecko 流通量/总量计算；未来 12/36 个月解锁和下一次大额解锁来自研究层 items.unlock
 * （必须带来源链接和日期；免费接口没有解锁计划数据）。
 * <ul>
 *   <li>流通率 &lt; 30% → 高稀释风险，最高 B</li>
 *   <li>没有可核实的解锁数据 → 解锁数据缺失，最高 B</li>
 *   <li>未来 36 个月解锁 &gt; 当前流通量 100% → 牛市窗口抛压（单独警示）</li>
 *   <li>研究写“已全部解锁”但流通率 &lt; 95%，或研究自己标了冲突 → 解锁数据冲突，不得评 A</li>
 * </ul>
 */
public final class UnlockRisk {

    private UnlockRisk() {
    }

    public record Result(Double circRatio, Double unlock12mPct, Double unlock36mPct, String nextUnlockDate, Double nextUnlockAmount,
                         Double supply12m, List<String> flags, boolean blocksA) {
        public String summary() {
            List<String> p = new ArrayList<>();
            p.add("流通率 " + (circRatio == null ? "缺失" : String.format(Locale.ROOT, "%.0f%%", circRatio * 100)));
            if (unlock12mPct != null) p.add(String.format(Locale.ROOT, "12个月解锁 %.0f%%", unlock12mPct));
            if (unlock36mPct != null) p.add(String.format(Locale.ROOT, "36个月解锁 %.0f%%", unlock36mPct));
            if (nextUnlockDate != null) p.add("下次解锁 " + nextUnlockDate + (nextUnlockAmount == null ? ""
                    : String.format(Locale.ROOT, "（%.3g 枚）", nextUnlockAmount)));
            return String.join("，", p);
        }
    }

    public static Result evaluate(Double circulating, Double totalSupply, JsonNode research) {
        List<String> flags = new ArrayList<>();
        boolean blocksA = false;
        Double ratio = circulating != null && totalSupply != null && totalSupply > 0 ? circulating / totalSupply : null;
        if (ratio != null && ratio < 0.30) {
            flags.add("高稀释风险（流通率 <30%）");
            blocksA = true;
        }
        JsonNode u = research == null ? null : research.path("items").path("unlock");
        boolean verified = u != null && !u.isMissingNode() && ChecklistEvaluator.item("unlock", "unlock", u).pass();
        Double u12 = verified ? num(u.get("unlock_12m_pct")) : null;
        Double u36 = verified ? num(u.get("unlock_36m_pct")) : null;
        String next = verified && u.hasNonNull("next_unlock_date") ? u.get("next_unlock_date").asText() : null;
        Double nextAmt = verified ? num(u.get("next_unlock_amount")) : null;
        if (!verified || u12 == null) {
            flags.add("解锁数据缺失");
            blocksA = true;
        }
        if (u36 != null && u36 > 100) flags.add("牛市窗口抛压（36 个月解锁超过当前流通量）");
        boolean fully = u != null && u.path("fully_unlocked").asBoolean(false);
        if ((fully && ratio != null && ratio < 0.95) || (u != null && u.path("conflict").asBoolean(false))) {
            flags.add("解锁数据冲突");
            blocksA = true;
        }
        Double supply12m = u12 != null && circulating != null ? circulating * (1 + u12 / 100) : null;
        return new Result(ratio, u12, u36, next, nextAmt, supply12m, flags, blocksA);
    }

    static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }
}
