package com.phoenixscout.research;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基本面质量分（V2.6 第四条，0–5 分，每项 1 分）。能用免费接口自动判断的由代码判断，其余必须由研究层给出“结论 + 来源链接 + 日期”。
 * <ol>
 *   <li>团队或机构背景可核实 —— 研究层 items.team</li>
 *   <li>产品使用或收入数据可核实 —— DefiLlama 有协议收入（自动），或研究层 items.economic_activity</li>
 *   <li>价值捕获可验证 —— DefiLlama 持币人收入 holdersRevenue &gt; 0（自动），或研究层 items.value_capture（须写明机制）</li>
 *   <li>主流交易所现货 —— CoinGecko 交易对（Binance/Coinbase/OKX/Bybit/Kraken/Upbit，自动）</li>
 *   <li>第三方审计 —— DefiLlama 审计记录（自动），或研究层 items.audit</li>
 * </ol>
 * 市值仅为项目自报且无第三方核实（研究层 self_reported_mcap=true）扣 1 分。
 * 规则：没研究过 → “待研究”，最高 B 级、不排除；研究过且 ≤1 分 → 排除；2–3 分最高 B；≥4 分才可能 A。
 */
public final class QualityScore {

    private QualityScore() {
    }

    public record Auto(boolean hasRevenue, boolean holdersRevenue, List<String> majorExchanges, boolean audited) { }

    public record Result(int score, Map<String, Boolean> items, List<String> missing, boolean researched, boolean selfReported,
                         boolean excluded, String cap, List<String> exchanges) {
        /** 研究前显示“未评分”，避免和“已研究但不合格”混淆；括号里是能自动判断的 4 项结果。 */
        public String label() {
            if (researched) return score + "/5";
            long auto = items.entrySet().stream().filter(e -> !e.getKey().equals(LABELS[0]) && e.getValue()).count();
            return "未评分（待研究；自动可查的 4 项满足 " + auto + " 项）";
        }

        /** 逐项显示满足/不满足（V2.6.1，用户要求一眼看出是哪一项没过）。团队背景只能由研究层核实，没研究过显示“待研究”。 */
        public String detail() {
            List<String> parts = new ArrayList<>();
            for (var e : items.entrySet()) {
                String k = e.getKey();
                String v;
                if (k.equals(LABELS[0]) && !researched) v = "待研究";
                else if (k.equals(LABELS[3]) && exchanges == null) v = "✗（交易所数据获取失败）";
                else if (k.equals(LABELS[3]) && e.getValue()) v = "✓（" + String.join("、", exchanges) + "）";
                else v = e.getValue() ? "✓" : "✗";
                parts.add(k + " " + v);
            }
            if (selfReported) parts.add("市值为自报（扣 1 分）");
            return String.join("｜", parts);
        }
    }

    public static final String[] LABELS = {"团队背景", "使用/收入数据", "价值捕获", "主流交易所现货", "第三方审计"};

    /** @param research 最新研究结果（没有为 null） */
    public static Result evaluate(Auto auto, JsonNode research) {
        JsonNode items = research == null ? null : research.path("items");
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put(LABELS[0], items != null && ok(items, "team"));
        m.put(LABELS[1], auto.hasRevenue() || (items != null && ok(items, "economic_activity")));
        m.put(LABELS[2], auto.holdersRevenue() || (items != null && ok(items, "value_capture")
                && !items.path("value_capture").path("mechanism").asText("").isBlank()));
        m.put(LABELS[3], auto.majorExchanges() != null && !auto.majorExchanges().isEmpty());
        m.put(LABELS[4], auto.audited() || (items != null && ok(items, "audit")));
        int score = (int) m.values().stream().filter(Boolean::booleanValue).count();
        boolean selfReported = research != null && research.path("self_reported_mcap").asBoolean(false);
        if (selfReported) score = Math.max(0, score - 1);
        List<String> missing = new ArrayList<>();
        m.forEach((k, v) -> { if (!v) missing.add(k); });
        if (selfReported) missing.add("市值为自报（扣 1 分）");
        boolean researched = research != null;
        boolean excluded = researched && score <= 1;
        String cap = excluded ? "排除" : score >= 4 && researched ? "A" : "B";
        return new Result(score, m, missing, researched, selfReported, excluded, cap,
                auto.majorExchanges() == null ? null : List.copyOf(auto.majorExchanges()));
    }

    static boolean ok(JsonNode items, String key) {
        return ChecklistEvaluator.item(key, key, items.path(key)).pass();
    }
}
