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
 * 长线模拟台账（V2.6，只记账，不下任何真实订单）。两本账：
 * <ul>
 *   <li><b>track（选币准确度）</b>：每个新进入推荐（A 级 + B 级观察）的币都记一笔，等额 1,000 美元——用来看选得准不准。</li>
 *   <li><b>portfolio（模拟组合）</b>：与用户的真实规则一致——高倍币总预算 5,000 美元，最多 2 个，每个 2,500 美元（受可用现金限制）。
 *       只买 A 级，或期望值 &gt; 0 的 B 级（A 优先，再按期望值）；首次建仓不限日期，之后空出来的位置只在每季度第一次扫描（季度复核）时补。
 *       止损、逻辑失效、止盈是风险控制，每天照常执行。</li>
 * </ul>
 * 出场规则（两本账相同，只按每日 00:00 UTC 收盘价判断，缺价不动作）：
 * <ol>
 *   <li>研究层判定逻辑失效（评级 C）→ 全部卖出。</li>
 *   <li>收盘 ≤ 防守价 → 全部卖出（防守价已上移到入场价时记为“保本止损”）。</li>
 *   <li>收盘 ≥ 估值目标 → 卖一半，防守价上移到入场价。无法估值（没有目标）的持仓不做这一步。</li>
 *   <li>卖过一半后（无目标的持仓：收盘曾达到入场价 2 倍后）：收盘从持有期最高收盘回撤 40% → 卖剩余（回撤止盈）。</li>
 *   <li>收盘 ≥ 入场价 2 倍 → 防守价上移到入场价。</li>
 *   <li>持有满 3 年 → 平仓。</li>
 * </ol>
 * 成本：买卖各 0.3%（手续费 0.1% + 滑点 0.2%）。另记录入场后 30/90/180/365 天的收益，与同期 BTC 和山寨中位数对比。
 */
public class SimLedger {

    public static final String TRACK = "track";
    public static final String PORTFOLIO = "portfolio";
    public static final int MAX_OPEN = 2;
    public static final double START_EQUITY = 5_000;
    public static final double TRACK_AMOUNT = 1_000;
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
        Double tp1 = t.get("tp1") == null ? null : num(t.get("tp1"));
        double stop = num(t.get("stop_loss"));
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
            boolean trailing = tp1Done || (tp1 == null && peak >= entry * 2);
            if (!tp1Done && tp1 != null && px >= tp1) {
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
            } else if (trailing && px <= peak * (1 - TRAIL)) {
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
            insert(TRACK, day, k, TRACK_AMOUNT, TRACK_AMOUNT * k.downside(), "等额 1,000 美元（检验选币准确度）");
        }
        // 模拟组合：A 级优先，再按期望值；只买 A 级或期望值 > 0 的 B 级
        List<Pick> eligible = new ArrayList<>();
        for (Pick k : picks) if ("A".equals(k.grade()) || (k.ev() != null && k.ev() > 0)) eligible.add(k);
        eligible.sort(java.util.Comparator.comparing((Pick k) -> !"A".equals(k.grade()))
                .thenComparing(k -> k.ev() == null ? Double.NEGATIVE_INFINITY : -k.ev()));
        int open = countOpen(PORTFOLIO);
        if (open >= MAX_OPEN) return;
        boolean everBought = countAll(PORTFOLIO) > 0;
        String qKey = quarterKey(day);
        boolean reviewDue = kv(qKey) == null;
        if (everBought && !reviewDue) return;          // 季度中途空出来的位置等季度复核再补
        boolean bought = false;
        for (Pick k : eligible) {
            if (open >= MAX_OPEN) break;
            if (hasOpen(PORTFOLIO, k.coinId())) continue;
            Summary s = summary(PORTFOLIO);
            Sizing sz = size(s.equity(), s.cash(), MAX_OPEN - open, k.downside());
            if (sz.amount() < MIN_AMOUNT) {
                ev.add(new Event(PORTFOLIO, "跳过", k.coinId(), k.name(), k.price(), null, null, "可用现金不足，未建仓"));
                continue;
            }
            insert(PORTFOLIO, day, k, sz.amount(), sz.risk(), sz.note());
            ev.add(new Event(PORTFOLIO, "入场", k.coinId(), k.name(), k.price(), null, null,
                    String.format(Locale.ROOT, "%s级，金额 $%.0f（%s），估值目标 %s，防守 %s，期望值 %s%s", k.grade(), sz.amount(), sz.note(),
                            k.takeProfit() == null ? "无法估值" : fmt(k.takeProfit()), fmt(k.stopLoss()),
                            k.ev() == null ? "样本不足" : String.format(Locale.ROOT, "%+.0f%%", k.ev() * 100),
                            everBought ? "（季度复核补仓）" : "（首次建仓）")));
            open++;
            bought = true;
        }
        if (bought || everBought) setKv(qKey, day.toString());
    }

    /** 季度键：2026-Q4。每季度第一次扫描是季度复核日。 */
    static String quarterKey(LocalDate day) {
        return "sim:review:" + day.getYear() + "-Q" + ((day.getMonthValue() - 1) / 3 + 1);
    }

    private String kv(String k) {
        List<String> v = jdbc.queryForList("SELECT v FROM kv WHERE k = ?", String.class, k);
        return v.isEmpty() ? null : v.get(0);
    }

    private void setKv(String k, String v) {
        jdbc.update("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", k, v);
    }

    private void insert(String book, LocalDate day, Pick k, double amount, double risk, String note) {
        double qty = amount * (1 - COST_RATE) / k.price();
        jdbc.update("INSERT INTO sim_trade(book, coin_id, name, path, grade, quality, researched, opened_day, entry_price, amount_usd,"
                        + " qty_initial, qty, risk_usd, ev, tp1, tp2, stop_loss, reason, size_note, status, peak_close, min_close, last_price, last_day)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'open',?,?,?,?)",
                book, k.coinId(), k.name(), k.path(), k.grade(), k.quality(), k.researched() ? 1 : 0, day.toString(), k.price(), amount,
                qty, qty, risk, k.ev(), k.takeProfit(), k.longTarget(), k.stopLoss(), k.reason(), note,
                k.price(), k.price(), k.price(), day.toString());
    }

    private boolean hasOpen(String book, String coin) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sim_trade WHERE book=? AND coin_id=? AND status='open'", Integer.class, book, coin);
        return n != null && n > 0;
    }

    private int countOpen(String book) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sim_trade WHERE book=? AND status='open'", Integer.class, book);
        return n == null ? 0 : n;
    }

    private int countAll(String book) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sim_trade WHERE book=?", Integer.class, book);
        return n == null ? 0 : n;
    }

    /** 等额分配：金额 = 当前权益 ÷ 剩余可用位置数（受可用现金限制），风险 = 金额 × 到防守价的跌幅。 */
    public static Sizing size(double equity, double cash, int slotsLeft, double downside) {
        double amount = equity / Math.max(1, slotsLeft);
        String note = String.format(Locale.ROOT, "总预算 %.0f 美元最多 %d 个，等额分配", START_EQUITY, MAX_OPEN);
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
        String[] cols = {"id", "book", "name", "path", "grade", "quality", "opened_day", "entry_price", "amount_usd", "risk_usd", "ev", "tp1", "stop_loss",
                "reason", "size_note", "status", "tp1_done", "last_price", "last_day", "peak_close", "min_close", "closed_day", "exit_price",
                "exit_reason", "pnl_usd", "pnl_pct", "r_multiple", "horizons"};
        StringBuilder s = new StringBuilder("﻿编号,账本,币,路径,评级,质量分,入场日,入场价,入场金额,风险金额,入场期望值,估值目标,防守价,入场原因,仓位说明,"
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
