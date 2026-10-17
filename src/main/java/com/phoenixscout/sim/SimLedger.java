package com.phoenixscout.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.scan.Pick;
import com.phoenixscout.scan.Returns;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * 长线模拟台账（V2.5，只记账，不下任何真实订单）。两本账：
 * <ul>
 *   <li><b>track（选币准确度）</b>：每个新进入推荐的币都记一笔，等额 1,000 美元，不受持仓数量限制——用来看选得准不准。</li>
 *   <li><b>portfolio（模拟组合）</b>：本金 10,000 美元，最多 10 个，按风险定仓——用来看照着投能不能稳定赚。
 *       每笔风险 = 当前权益 × 1%（盈亏比 ≥20 ×1.5，≥10 ×1.25）；金额 = 风险 ÷ 到防守价的跌幅；单笔 ≤ 权益 20%，≤ 可用现金。</li>
 * </ul>
 * 出场规则（两本账相同，只按每日 00:00 UTC 收盘价判断，缺价不动作）：
 * <ol>
 *   <li>研究层判定逻辑失效（评级 C）→ 全部卖出。</li>
 *   <li>收盘 ≤ 防守价 → 全部卖出（防守价已上移到入场价时记为“保本止损”）。</li>
 *   <li>收盘 ≥ 第一目标 → 卖一半，防守价上移到入场价。</li>
 *   <li>卖过一半后：收盘 ≥ 长期目标 → 卖剩余；或收盘从持有期最高收盘回撤 40% → 卖剩余（回撤止盈）。</li>
 *   <li>收盘 ≥ 入场价 2 倍 → 防守价上移到入场价。</li>
 *   <li>持有满 3 年 → 平仓。</li>
 * </ol>
 * 成本：买卖各 0.3%（手续费 0.1% + 滑点 0.2%）。另记录入场后 30/90/180/365 天的收益，与同期 BTC 和山寨中位数对比。
 */
public class SimLedger {

    public static final String TRACK = "track";
    public static final String PORTFOLIO = "portfolio";
    public static final int MAX_OPEN = 10;
    public static final double START_EQUITY = 10_000;
    public static final double TRACK_AMOUNT = 1_000;
    public static final double RISK_PCT = 0.01;
    public static final double MAX_POSITION_PCT = 0.20;
    public static final double MIN_AMOUNT = 100;
    public static final double COST_RATE = 0.003;
    public static final double TRAIL = 0.40;
    public static final int MAX_DAYS = 1095;
    public static final int[] HORIZONS = {30, 90, 180, 365};
    /** 选币台账的持仓每 7 天集中补一次日线（按日逐天回放），控制接口请求量；模拟组合每天补。 */
    public static final int TRACK_REFRESH_DAYS = 7;

    public record Event(String book, String kind, String coinId, String name, double price, Double pnlUsd, Double pnlPct, String note) { }

    public record Summary(String book, int open, double invested, double openValue, double unrealized, int closed,
                          double realized, int wins, int losses, double equity, double cash) { }

    public record Sizing(double amount, double risk, String note) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public SimLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 今天需要补日线的币：组合持仓（每天）、选币台账中超过 7 天没更新的持仓、到了观察期需要取价的币。 */
    public Set<String> coinsNeedingHistory(LocalDate day) {
        Set<String> out = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT DISTINCT coin_id FROM sim_trade WHERE status='open' AND (book=? OR last_day <= ?)", String.class,
                PORTFOLIO, day.minusDays(TRACK_REFRESH_DAYS).toString()));
        for (Map<String, Object> t : jdbc.queryForList("SELECT coin_id, opened_day, horizons FROM sim_trade WHERE opened_day >= ?",
                day.minusDays(366).toString())) {
            LocalDate opened = LocalDate.parse((String) t.get("opened_day"));
            String h = (String) t.get("horizons");
            for (int n : HORIZONS) {
                if (!opened.plusDays(n).isAfter(day) && !h.contains("\"" + n + "\"")) out.add((String) t.get("coin_id"));
            }
        }
        return out;
    }

    /**
     * 一天的处理：回放持仓到今天 → 记录观察期收益 → 入场 → 记权益。
     *
     * @param invalid 研究层判定逻辑失效的币（最新评级 C）
     * @param altIds  山寨中位数基准的成分（用于观察期对比）
     */
    public List<Event> run(LocalDate day, Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist, List<Pick> picks,
                           Set<String> invalid, Collection<String> altIds) {
        List<Event> ev = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("SELECT * FROM sim_trade WHERE status='open' ORDER BY id")) {
            replay(t, day, hist.get((String) t.get("coin_id")), invalid, ev);
        }
        recordHorizons(day, hist, altIds);
        enter(day, picks, ev);
        for (String book : List.of(TRACK, PORTFOLIO)) {
            Summary s = summary(book);
            jdbc.update("INSERT OR REPLACE INTO sim_equity(day, book, equity, invested, open_cnt) VALUES (?,?,?,?,?)",
                    day.toString(), book, s.equity() + s.unrealized(), s.invested(), s.open());
        }
        return ev;
    }

    private void replay(Map<String, Object> t, LocalDate day, NavigableMap<LocalDate, DailyPoint> h, Set<String> invalid, List<Event> ev) {
        long id = (long) num(t.get("id"));
        String book = (String) t.get("book"), coin = (String) t.get("coin_id"), name = (String) t.get("name");
        LocalDate opened = LocalDate.parse((String) t.get("opened_day"));
        LocalDate last = LocalDate.parse((String) t.get("last_day"));
        double entry = num(t.get("entry_price")), amount = num(t.get("amount_usd")), risk = num(t.get("risk_usd"));
        double qty0 = num(t.get("qty_initial")), qty = num(t.get("qty"));
        double tp1 = num(t.get("tp1")), stop = num(t.get("stop_loss"));
        Double tp2 = t.get("tp2") == null ? null : num(t.get("tp2"));
        boolean tp1Done = num(t.get("tp1_done")) == 1;
        double realized = num(t.get("realized_usd")), peak = num(t.get("peak_close")), min = num(t.get("min_close"));
        double lastPx = num(t.get("last_price"));
        LocalDate lastDay = last;
        String exit = null;
        double exitPx = 0;

        // 逻辑失效：今天按收盘价卖出（只看今天，不追溯）
        if (invalid != null && invalid.contains(coin)) {
            var px = Returns.price(h, day);
            if (px.isPresent()) {
                exit = "逻辑失效";
                exitPx = px.get();
                lastDay = day;
            }
        }
        for (LocalDate d = last.plusDays(1); exit == null && !d.isAfter(day); d = d.plusDays(1)) {
            var close = Returns.price(h, d);
            if (close.isEmpty()) continue;
            double px = close.get();
            lastPx = px;
            lastDay = d;
            peak = Math.max(peak, px);
            min = Math.min(min, px);
            if (px <= stop) {
                exit = stop >= entry ? "保本止损" : "止损";
                exitPx = px;
                break;
            }
            if (!tp1Done && px >= tp1) {
                double sell = qty / 2;
                double part = sell * px * (1 - COST_RATE) - amount * (sell / qty0);
                realized += part;
                qty -= sell;
                tp1Done = true;
                stop = Math.max(stop, entry);
                ev.add(new Event(book, "止盈一半", coin, name, px, part, part / (amount / 2), "到第一目标，防守价上移到入场价"));
            } else if (tp1Done && tp2 != null && px >= tp2) {
                exit = "长期目标止盈";
                exitPx = px;
                break;
            } else if (tp1Done && px <= peak * (1 - TRAIL)) {
                exit = "回撤止盈";
                exitPx = px;
                break;
            }
            if (px >= entry * 2 && stop < entry) {
                stop = entry;
                ev.add(new Event(book, "保本", coin, name, px, null, null, "翻倍，防守价上移到入场价"));
            }
            if (ChronoUnit.DAYS.between(opened, d) >= MAX_DAYS) {
                exit = "到期";
                exitPx = px;
                break;
            }
        }
        if (exit != null) {
            double pnl = realized + qty * exitPx * (1 - COST_RATE) - amount * (qty / qty0);
            double r = risk > 0 ? pnl / risk : 0;
            jdbc.update("UPDATE sim_trade SET status='closed', qty=0, realized_usd=?, peak_close=?, min_close=?, last_price=?, last_day=?,"
                            + " closed_day=?, exit_price=?, exit_reason=?, pnl_usd=?, pnl_pct=?, r_multiple=?, tp1_done=?, stop_loss=? WHERE id=?",
                    pnl, peak, min, exitPx, lastDay.toString(), lastDay.toString(), exitPx, exit, pnl, pnl / amount, r, tp1Done ? 1 : 0, stop, id);
            ev.add(new Event(book, exit, coin, name, exitPx, pnl, pnl / amount,
                    "入场 " + fmt(entry) + " → " + fmt(exitPx) + String.format(Locale.ROOT, "，%+.1fR", r)));
        } else {
            jdbc.update("UPDATE sim_trade SET qty=?, realized_usd=?, peak_close=?, min_close=?, last_price=?, last_day=?, tp1_done=?, stop_loss=?"
                    + " WHERE id=?", qty, realized, peak, min, lastPx, lastDay.toString(), tp1Done ? 1 : 0, stop, id);
        }
    }

    private void recordHorizons(LocalDate day, Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist, Collection<String> altIds) {
        for (Map<String, Object> t : jdbc.queryForList("SELECT id, coin_id, opened_day, horizons FROM sim_trade WHERE opened_day >= ?",
                day.minusDays(366).toString())) {
            LocalDate opened = LocalDate.parse((String) t.get("opened_day"));
            try {
                ObjectNode h = (ObjectNode) om.readTree((String) t.get("horizons"));
                boolean changed = false;
                for (int n : HORIZONS) {
                    LocalDate at = opened.plusDays(n);
                    if (at.isAfter(day) || h.has(String.valueOf(n))) continue;
                    var coin = Returns.ret(hist.get((String) t.get("coin_id")), opened, at);
                    if (coin.isEmpty()) continue;    // 还没有那天的收盘价，下次再记
                    ObjectNode o = h.putObject(String.valueOf(n));
                    o.put("coin", coin.get());
                    var btc = Returns.ret(hist.get("bitcoin"), opened, at);
                    if (btc.isPresent()) o.put("btc", btc.get()); else o.putNull("btc");
                    List<Double> alts = new ArrayList<>();
                    for (String a : altIds) Returns.ret(hist.get(a), opened, at).ifPresent(alts::add);
                    var med = alts.size() >= 10 ? Returns.median(alts) : java.util.Optional.<Double>empty();
                    if (med.isPresent()) o.put("alt", med.get()); else o.putNull("alt");
                    changed = true;
                }
                if (changed) jdbc.update("UPDATE sim_trade SET horizons=? WHERE id=?", om.writeValueAsString(h), t.get("id"));
            } catch (IOException e) {
                // horizons 字段损坏时跳过，不影响其他记录
            }
        }
    }

    private void enter(LocalDate day, List<Pick> picks, List<Event> ev) {
        // 选币台账：每个新进入推荐的币都记一笔（已有未平仓的不重复记）
        for (Pick k : picks) {
            if (hasOpen(TRACK, k.coinId())) continue;
            double down = 1 - k.stopLoss() / k.price();
            insert(TRACK, day, k, TRACK_AMOUNT, TRACK_AMOUNT * down, "等额 1,000 美元（检验选币准确度）");
        }
        // 模拟组合：按盈亏比顺序补满空位，按风险定仓
        int open = countOpen(PORTFOLIO);
        for (Pick k : picks) {
            if (open >= MAX_OPEN) break;
            if (hasOpen(PORTFOLIO, k.coinId())) continue;
            Summary s = summary(PORTFOLIO);
            Sizing sz = size(s.equity(), s.cash(), k.ratio(), 1 - k.stopLoss() / k.price());
            if (sz.amount() < MIN_AMOUNT) {
                ev.add(new Event(PORTFOLIO, "跳过", k.coinId(), k.name(), k.price(), null, null, "可用现金不足，未建仓"));
                continue;
            }
            insert(PORTFOLIO, day, k, sz.amount(), sz.risk(), sz.note());
            ev.add(new Event(PORTFOLIO, "入场", k.coinId(), k.name(), k.price(), null, null,
                    String.format(Locale.ROOT, "金额 $%.0f（%s），第一目标 %s%s，防守 %s，盈亏比 %.1f", sz.amount(), sz.note(),
                            fmt(k.takeProfit()), k.longTarget() == null ? "" : "，长期目标 " + fmt(k.longTarget()),
                            fmt(k.stopLoss()), k.ratio())));
            open++;
        }
    }

    private void insert(String book, LocalDate day, Pick k, double amount, double risk, String note) {
        double qty = amount * (1 - COST_RATE) / k.price();
        jdbc.update("INSERT INTO sim_trade(book, coin_id, name, path, opened_day, entry_price, amount_usd, qty_initial, qty, risk_usd, ratio,"
                        + " tp1, tp2, stop_loss, reason, size_note, status, peak_close, min_close, last_price, last_day)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'open',?,?,?,?)",
                book, k.coinId(), k.name(), k.path(), day.toString(), k.price(), amount, qty, qty, risk, k.ratio(),
                k.takeProfit(), k.longTarget(), k.stopLoss(), k.reason(), note, k.price(), k.price(), k.price(), day.toString());
    }

    private boolean hasOpen(String book, String coin) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sim_trade WHERE book=? AND coin_id=? AND status='open'", Integer.class, book, coin);
        return n != null && n > 0;
    }

    private int countOpen(String book) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sim_trade WHERE book=? AND status='open'", Integer.class, book);
        return n == null ? 0 : n;
    }

    /** 按风险定仓位：风险金额 = 权益 × 1% × 盈亏比系数；金额 = 风险 ÷ 跌幅，受单笔 20% 和可用现金限制。 */
    public static Sizing size(double equity, double cash, double ratio, double downside) {
        double factor = ratio >= 20 ? 1.5 : ratio >= 10 ? 1.25 : 1.0;
        double risk = equity * RISK_PCT * factor;
        double amount = risk / Math.max(downside, 0.01);
        String note = String.format(Locale.ROOT, "风险 %.0f 美元 = 权益 1%%×%.2f，跌到防守 %.0f%%", risk, factor, downside * 100);
        if (amount > equity * MAX_POSITION_PCT) {
            amount = equity * MAX_POSITION_PCT;
            note += "，受单笔 20% 上限";
        }
        if (amount > cash) {
            amount = Math.max(0, cash);
            note += "，受可用现金限制";
        }
        return new Sizing(amount, amount * downside, note);
    }

    /** 某本账的汇总（按最近收盘价）。权益 = 本金 + 已实现（含已卖出的一半）；可用现金 = 权益 − 未平仓成本。 */
    public Summary summary(String book) {
        double invested = 0, value = 0, partial = 0;
        int open = 0;
        for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM sim_trade WHERE book=? AND status='open'", book)) {
            open++;
            double cost = num(p.get("amount_usd")) * num(p.get("qty")) / num(p.get("qty_initial"));
            invested += cost;
            value += num(p.get("qty")) * num(p.get("last_price")) * (1 - COST_RATE);
            partial += num(p.get("realized_usd"));
        }
        Map<String, Object> c = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(pnl_usd),0) s, COALESCE(SUM(CASE WHEN pnl_usd>0 THEN 1 ELSE 0 END),0) w"
                + " FROM sim_trade WHERE book=? AND status='closed'", book);
        int n = (int) num(c.get("n")), w = (int) num(c.get("w"));
        double realized = num(c.get("s")) + partial;
        double equity = START_EQUITY + realized;
        return new Summary(book, open, invested, value, value - invested, n, realized, w, n - w, equity, equity - invested);
    }

    public void exportCsv(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        String[] cols = {"id", "book", "name", "path", "opened_day", "entry_price", "amount_usd", "risk_usd", "ratio", "tp1", "tp2", "stop_loss",
                "reason", "size_note", "status", "tp1_done", "last_price", "last_day", "peak_close", "min_close", "closed_day", "exit_price",
                "exit_reason", "pnl_usd", "pnl_pct", "r_multiple", "horizons"};
        StringBuilder s = new StringBuilder("﻿编号,账本,币,路径,入场日,入场价,入场金额,风险金额,入场盈亏比,第一目标,长期目标,防守价,入场原因,仓位说明,"
                + "状态,已卖一半,最新价,最新日,持有期最高收盘,持有期最低收盘,平仓日,平仓价,平仓原因,盈亏金额,盈亏比例,R倍数,观察期收益\n");
        for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM sim_trade ORDER BY id")) {
            List<String> v = new ArrayList<>();
            for (String col : cols) {
                Object o = p.get(col);
                String t = o == null ? "" : o.toString();
                if (col.equals("book")) t = TRACK.equals(t) ? "选币台账" : "模拟组合";
                v.add(t.contains(",") || t.contains("\"") ? "\"" + t.replace("\"", "\"\"") + "\"" : t);
            }
            s.append(String.join(",", v)).append("\n");
        }
        Files.writeString(file, s.toString(), StandardCharsets.UTF_8);
    }

    static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    static String fmt(double p) {
        return com.phoenixscout.report.RiskReward.price(p);
    }
}
