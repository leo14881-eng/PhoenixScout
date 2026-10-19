package com.phoenixscout.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.market.MarketCoin;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 重点观察名单（用户指定，classpath:watchlist.json）。每天记录价格、收入、持币人收入，
 * 判断名单里写好的信号；信号从“不满足”变成“满足”时推送提醒。不进入 A/B 推荐，也不记模拟台账。
 * 收入来自 DefiLlama（按 slug 汇总）；合作链分成 = 合作链协议收入 × 分成比例，标注为估算。
 */
public final class WatchList {

    public record Entry(String coinId, String name, String added, String reason, List<String> revenueSlugs,
                        List<Partner> partners, List<String> signals) { }

    public record Partner(String slug, String name, double share) { }

    public record Status(Entry entry, Double price, Double marketCap, Double fdv, Double circRatio, Double r30, Double r90,
                         Double revenue30, Double revenuePrev30, Double partner30, Double partnerPrev30, Double holders30,
                         Boolean revenueGrowing, String revenueNote, boolean holdersPositive, List<String> newlyTriggered) {
        public Double total30() {
            return revenue30 == null ? null : revenue30 + (partner30 == null ? 0 : partner30);
        }

        public Double totalPrev30() {
            return revenuePrev30 == null ? null : revenuePrev30 + (partnerPrev30 == null ? 0 : partnerPrev30);
        }
    }

    static final String SIG_REVENUE = "分成收入连续增长", SIG_HOLDERS = "持币人收入出现";

    private WatchList() {
    }

    public static List<Entry> load() {
        try (InputStream in = WatchList.class.getResourceAsStream("/watchlist.json")) {
            if (in == null) return List.of();
            List<Entry> out = new ArrayList<>();
            for (JsonNode n : new ObjectMapper().readTree(in)) {
                List<String> slugs = new ArrayList<>();
                n.path("revenueSlugs").forEach(x -> slugs.add(x.asText()));
                List<Partner> ps = new ArrayList<>();
                n.path("partnerShares").forEach(x -> ps.add(new Partner(x.path("slug").asText(), x.path("name").asText(), x.path("share").asDouble())));
                List<String> sig = new ArrayList<>();
                n.path("signals").forEach(x -> sig.add(x.asText()));
                out.add(new Entry(n.path("coinId").asText(), n.path("name").asText(), n.path("added").asText(), n.path("reason").asText(),
                        slugs, ps, sig));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("watchlist.json 无法读取：" + e.getMessage(), e);
        }
    }

    /**
     * 计算今天的状态并写入 watch_metric；与 30 天前的记录比较判断“连续两个 30 天环比增长”。
     * newlyTriggered = 今天满足、上一次记录不满足的信号。
     */
    public static Status evaluate(JdbcTemplate jdbc, Entry e, LocalDate day, MarketCoin mc, NavigableMap<LocalDate, DailyPoint> h,
                                  Map<String, double[]> revenue, Map<String, double[]> holders) {
        Double rev = null, revPrev = null, part = null, partPrev = null, hold = null;
        for (String slug : e.revenueSlugs()) {
            double[] v = revenue.get(slug);
            if (v == null) continue;
            if (!Double.isNaN(v[0])) rev = (rev == null ? 0 : rev) + v[0];
            if (!Double.isNaN(v[1])) revPrev = (revPrev == null ? 0 : revPrev) + v[1];
            double[] hv = holders.get(slug);
            if (hv != null && !Double.isNaN(hv[0])) hold = (hold == null ? 0 : hold) + hv[0];
        }
        for (Partner p : e.partners()) {
            double[] v = revenue.get(p.slug());
            if (v == null) continue;
            if (!Double.isNaN(v[0])) part = (part == null ? 0 : part) + v[0] * p.share();
            if (!Double.isNaN(v[1])) partPrev = (partPrev == null ? 0 : partPrev) + v[1] * p.share();
        }
        Double total = rev == null ? null : rev + (part == null ? 0 : part);
        Double totalPrev = revPrev == null ? null : revPrev + (partPrev == null ? 0 : partPrev);
        // 30 天前那次记录的“近 30 天 / 前 30 天”，用来判断连续两次环比
        List<Map<String, Object>> old = jdbc.queryForList("SELECT total30, total_prev30 FROM watch_metric WHERE coin_id = ? AND day <= ? ORDER BY day DESC LIMIT 1",
                e.coinId(), day.minusDays(28).toString());
        Boolean growing = null;
        String note;
        if (total == null || totalPrev == null) note = "收入数据缺失";
        else if (total <= totalPrev) {
            growing = false;
            note = String.format(Locale.ROOT, "近 30 天 %s，比前 30 天 %s 下降", usd(total), usd(totalPrev));
        } else if (old.isEmpty() || old.get(0).get("total30") == null || old.get(0).get("total_prev30") == null) {
            note = String.format(Locale.ROOT, "近 30 天 %s，比前 30 天 %s 增长；还需 30 天后再确认一次", usd(total), usd(totalPrev));
        } else {
            double o = ((Number) old.get(0).get("total30")).doubleValue(), op = ((Number) old.get(0).get("total_prev30")).doubleValue();
            growing = o > op;
            note = String.format(Locale.ROOT, "近 30 天 %s > 前 30 天 %s；上一期 %s %s %s", usd(total), usd(totalPrev), usd(o), o > op ? ">" : "≤", usd(op));
        }
        boolean holdersPos = hold != null && hold > 0;
        Double price = Returns.price(h, day).orElse(mc == null ? null : mc.price());
        Double circ = mc == null || mc.circulating() == null || mc.totalSupply() == null || mc.totalSupply() <= 0 ? null
                : mc.circulating() / mc.totalSupply();

        List<Map<String, Object>> prev = jdbc.queryForList("SELECT revenue_growing, holders_positive FROM watch_metric WHERE coin_id = ? AND day < ? ORDER BY day DESC LIMIT 1",
                e.coinId(), day.toString());
        List<String> fired = new ArrayList<>();
        boolean prevGrowing = !prev.isEmpty() && prev.get(0).get("revenue_growing") != null && ((Number) prev.get(0).get("revenue_growing")).intValue() == 1;
        boolean prevHolders = !prev.isEmpty() && ((Number) prev.get(0).get("holders_positive")).intValue() == 1;
        if (Boolean.TRUE.equals(growing) && !prevGrowing) fired.add(SIG_REVENUE);
        if (holdersPos && !prevHolders) fired.add(SIG_HOLDERS);
        jdbc.update("INSERT OR REPLACE INTO watch_metric(day, coin_id, price, market_cap, total30, total_prev30, holders30, revenue_growing, holders_positive)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)", day.toString(), e.coinId(), price, mc == null ? null : mc.marketCap(), total, totalPrev, hold,
                growing == null ? null : growing ? 1 : 0, holdersPos ? 1 : 0);
        return new Status(e, price, mc == null ? null : mc.marketCap(), mc == null ? null : mc.fdv(), circ,
                Returns.rolling(h, day, 30).orElse(null), Returns.rolling(h, day, 90).orElse(null),
                rev, revPrev, part, partPrev, hold, growing, note, holdersPos, fired);
    }

    public static String line(Status s) {
        StringBuilder b = new StringBuilder();
        b.append(s.entry().name()).append(" 现价 ").append(s.price() == null ? "缺失" : com.phoenixscout.report.RiskReward.price(s.price()))
                .append("｜流通市值 ").append(usd(s.marketCap())).append("｜30天 ").append(com.phoenixscout.report.ReportWriter.pct(s.r30()))
                .append("｜90天 ").append(com.phoenixscout.report.ReportWriter.pct(s.r90()));
        b.append("\n   收入（近 30 天）：自身 ").append(usd(s.revenue30())).append("（前 30 天 ").append(usd(s.revenuePrev30())).append("）");
        if (!s.entry().partners().isEmpty()) b.append(" + 合作链分成估算 ").append(usd(s.partner30())).append("（前 30 天 ").append(usd(s.partnerPrev30())).append("）");
        b.append("\n   ").append(SIG_REVENUE).append("：").append(s.revenueGrowing() == null ? "待确认" : s.revenueGrowing() ? "✓" : "✗")
                .append("（").append(s.revenueNote()).append("）");
        b.append("\n   ").append(SIG_HOLDERS).append("：").append(s.holdersPositive() ? "✓（近 30 天 " + usd(s.holders30()) + "）" : "✗（持币人收入为 0）");
        return b.toString();
    }

    static String usd(Double v) {
        return com.phoenixscout.report.ReportWriter.usd(v);
    }
}
