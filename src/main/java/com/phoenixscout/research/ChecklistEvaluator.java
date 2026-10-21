package com.phoenixscout.research;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第十四条 A 级 9 项硬性条件的代码校验。研究层只提供结论和来源，是否通过由这里决定：
 * 每项必须 ok=true 且至少一条带 http(s) 链接和 yyyy-MM-dd 日期的来源；第 7 项由代码按情景计算。
 */
public final class ChecklistEvaluator {

    private ChecklistEvaluator() {
    }

    public record Source(String url, String date) { }

    public record Item(String key, String label, boolean pass, String claim, String reason, List<Source> sources) { }

    public record Result(String grade, List<Item> items, List<String> failed, List<ScenarioCalculator.Scenario> scenarios,
                         List<ScenarioCalculator.Target> targets, ScenarioCalculator.Inputs inputs, List<String> notes) { }

    static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("identity", "1 代币身份、合约、供应量可核实");
        LABELS.put("supply_verifiable", "2 流通市值、FDV、未来12个月供应变化可核实");
        LABELS.put("economic_activity", "3 真实经济活动");
        LABELS.put("value_capture", "4 可验证的价值捕获");
        LABELS.put("liquidity", "5 主要交易所现货流动性足够");
        LABELS.put("no_security_issue", "6 无未解决的重大安全/治理问题");
        LABELS.put("valuation_5x", "7 合理的5倍以上估值情景");
        LABELS.put("data_consistent", "8 数据来源可靠、无重大矛盾");
        LABELS.put("supply_growth", "9 未来12个月供应增长≤25%且流通量≥总量50%");
    }

    /**
     * @param path        候选来自哪条路径（"C" 时额外要求审计）
     * @param marketPrice 服务端最新行情价（用于情景推算和与研究价格对比；为 null 时用研究给的价格）
     */
    public static Result evaluate(JsonNode research, String path, Double marketPrice) {
        List<String> notes = new ArrayList<>();
        JsonNode items = research.path("items");
        List<Item> out = new ArrayList<>();
        ScenarioCalculator.Inputs inputs = inputs(research.path("scenario_inputs"), marketPrice, notes);
        List<ScenarioCalculator.Scenario> scen = inputs == null ? List.of() : ScenarioCalculator.scenarios(inputs);
        List<ScenarioCalculator.Target> targets = inputs == null ? List.of() : ScenarioCalculator.targets(inputs);

        for (var e : LABELS.entrySet()) {
            String key = e.getKey();
            if (key.equals("valuation_5x")) {
                boolean ok = inputs != null && scen.size() == 3 && ScenarioCalculator.fiveXPlausible(scen);
                out.add(new Item(key, e.getValue(), ok, null,
                        inputs == null ? "情景输入不全（价格、流通量、收入、增速、同赛道倍数）"
                                : scen.size() < 3 ? "三个情景的增速或倍数不全"
                                : ok ? "基准或乐观情景 36 个月内可达 5 倍" : "基准和乐观情景 36 个月内都达不到 5 倍",
                        List.of()));
                continue;
            }
            out.add(item(key, e.getValue(), items.path(key)));
        }
        if ("C".equals(path)) out.add(item("audit", "路径C 第三方审计", items.path("audit")));

        // 额外的硬性细则
        for (int i = 0; i < out.size(); i++) {
            Item it = out.get(i);
            if (!it.pass()) continue;
            JsonNode n = items.path(it.key());
            if (it.key().equals("value_capture") && n.path("mechanism").asText("").isBlank()) {
                out.set(i, fail(it, "未写明价值捕获机制"));
            }
            if (it.key().equals("supply_growth")) {
                JsonNode g = n.get("supply_growth_12m_pct");
                boolean strong = !n.path("strong_evidence").asText("").isBlank();
                if (g == null || !g.isNumber()) out.set(i, fail(it, "未给出未来12个月供应增长数值"));
                else if (g.asDouble() > 25 && !strong) {
                    out.set(i, fail(it, "供应增长 " + g.asDouble() + "% 超过 25% 且没有需求抵消的强证据"));
                } else if (inputs != null && inputs.totalSupply() != null && inputs.totalSupply() > 0
                        && inputs.circulating() / inputs.totalSupply() < 0.5 && !strong) {
                    out.set(i, fail(it, String.format(java.util.Locale.ROOT, "流通量只占总量 %.0f%%（要求 ≥50%%），且没有强证据",
                            inputs.circulating() / inputs.totalSupply() * 100)));
                }
            }
        }
        List<String> failed = out.stream().filter(x -> !x.pass()).map(Item::key).toList();
        String hint = research.path("grade_hint").asText("");
        // 研究层的判断只能让评级更严：研究认定 C（淘汰/仅观察）就是 C，认定 B 就最多 B（V2.6.5，NEAR 误评 A 后修正）
        String grade = "C".equals(hint) ? "C" : failed.isEmpty() && !"B".equals(hint) ? "A" : "B";
        if (failed.isEmpty() && !"A".equals(grade)) notes.add("9 项代码校验通过，但研究层判定为 " + hint + " 级，按更严的结论");
        return new Result(grade, out, failed, scen, targets, inputs, notes);
    }

    static Item fail(Item it, String reason) {
        return new Item(it.key(), it.label(), false, it.claim(), reason, it.sources());
    }

    static Item item(String key, String label, JsonNode n) {
        List<Source> src = new ArrayList<>();
        for (JsonNode s : n.path("sources")) {
            String url = s.path("url").asText("");
            String date = s.path("date").asText("");
            if (url.matches("https?://\\S+") && validDate(date)) src.add(new Source(url, date));
        }
        String claim = n.path("claim").asText(null);
        if (n.isMissingNode() || n.isNull()) return new Item(key, label, false, null, "研究未覆盖", src);
        JsonNode ok = n.get("ok");
        if (ok == null || ok.isNull()) return new Item(key, label, false, claim, "未能核实", src);
        if (!ok.asBoolean()) return new Item(key, label, false, claim, "研究结论为不满足", src);
        if (src.isEmpty()) return new Item(key, label, false, claim, "缺少带链接和日期的来源", src);
        return new Item(key, label, true, claim, "已核实", src);
    }

    static boolean validDate(String d) {
        try {
            LocalDate.parse(d);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static ScenarioCalculator.Inputs inputs(JsonNode si, Double marketPrice, List<String> notes) {
        Double rp = num(si.get("price_usd"));
        Double price = marketPrice != null ? marketPrice : rp;
        if (marketPrice != null && rp != null && Math.abs(rp / marketPrice - 1) > 0.2) {
            notes.add("研究价格与最新行情相差超过 20%，情景按最新行情价计算");
        }
        Double circ = num(si.get("circulating_supply"));
        Double rev = num(si.get("annual_revenue_usd"));
        if (price == null || price <= 0 || circ == null || circ <= 0 || rev == null || rev <= 0) return null;
        Map<Integer, Double> sbm = new HashMap<>();
        si.path("supply_by_month").fields().forEachRemaining(f -> {
            try {
                Double v = num(f.getValue());
                if (v != null) sbm.put(Integer.parseInt(f.getKey()), v);
            } catch (NumberFormatException ignored) {
                // 忽略非月份键
            }
        });
        if (sbm.isEmpty()) notes.add("缺少未来供应表，情景按当前流通量计算（会高估收益）");
        Map<String, Double> g = new HashMap<>();
        si.path("revenue_growth").fields().forEachRemaining(f -> { Double v = num(f.getValue()); if (v != null) g.put(f.getKey(), v); });
        // 收入增速上限（与初步推算一致）：基准 ≤50%/年，乐观 ≤100%/年，保守不高于基准——避免把一个季度的环比增速当成每年复利
        capGrowth(g, "base", 0.5, notes);
        capGrowth(g, "optimistic", 1.0, notes);
        if (g.containsKey("conservative") && g.containsKey("base") && g.get("conservative") > g.get("base")) g.put("conservative", g.get("base"));
        Map<String, Double> m = new HashMap<>();
        si.path("peer_multiples").fields().forEachRemaining(f -> { Double v = num(f.getValue()); if (v != null && v > 0) m.put(f.getKey(), v); });
        return new ScenarioCalculator.Inputs(price, circ, num(si.get("total_supply")), sbm, rev, g, m);
    }

    static void capGrowth(Map<String, Double> g, String k, double cap, List<String> notes) {
        Double v = g.get(k);
        if (v != null && v > cap) {
            g.put(k, cap);
            notes.add(String.format(java.util.Locale.ROOT, "研究给出的%s收入增速 %.0f%%/年超过上限，按 %.0f%% 计算",
                    k.equals("base") ? "基准" : "乐观", v * 100, cap * 100));
        }
    }

    static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }
}
