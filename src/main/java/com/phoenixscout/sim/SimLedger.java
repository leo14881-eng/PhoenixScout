package com.phoenixscout.sim;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 模拟仓：只记账，不下任何真实订单，用于复盘推荐质量。
 * <ul>
 *   <li>入场：每天从“盈亏比最高的推荐”里按顺序补满空位，最多同时 10 个，按当天 00:00 UTC 收盘价成交。</li>
 *   <li>仓位自定（按风险）：模拟本金 10,000 美元；每笔风险 = 当前权益 × 1%，盈亏比 ≥20 放大到 1.5 倍、≥10 放大到 1.25 倍；
 *       金额 = 风险金额 ÷ 到防守价的跌幅；单笔不超过权益 20%，不超过可用现金，不足 100 美元不建仓。</li>
 *   <li>止盈：收盘价 ≥ 目标价全部卖出。止损：收盘价 ≤ 防守价全部卖出。</li>
 *   <li>保本：收盘价曾达到入场价 +50% 后，防守价上移到入场价。</li>
 *   <li>到期：持有满 180 天按当天收盘价平仓。</li>
 *   <li>成本：买卖各 0.1% 手续费 + 0.2% 滑点，计入盈亏。</li>
 * </ul>
 * 只用日线收盘价判断（不看盘中），缺当天收盘价时不动作，不用旧价冒充。
 */
public class SimLedger {

    public static final int MAX_OPEN = 10;
    public static final double START_EQUITY = 10_000;
    public static final double RISK_PCT = 0.01;
    public static final double MAX_POSITION_PCT = 0.20;
    public static final double MIN_AMOUNT = 100;
    public static final double COST_RATE = 0.003;      // 单边 0.1% 手续费 + 0.2% 滑点
    public static final int MAX_DAYS = 180;

    public record Event(String kind, String coinId, String name, double price, Double pnlUsd, Double pnlPct, String note) { }

    public record Summary(int open, double openValue, double unrealized, int closed, double realized, int wins, int losses,
                          double equity, double cash, double invested) { }

    private final JdbcTemplate jdbc;

    public SimLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> openCoinIds() {
        return jdbc.queryForList("SELECT coin_id FROM sim_position WHERE status = 'open'", String.class);
    }

    /** 先按今日收盘检查持仓（止盈/止损/保本/到期），再按推荐顺序补仓。同一天重复运行不会重复入场或平仓。 */
    public List<Event> run(LocalDate day, Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist, List<Pick> picks) {
        List<Event> ev = new ArrayList<>();
        for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM sim_position WHERE status = 'open' ORDER BY id")) {
            long id = ((Number) p.get("id")).longValue();
            String coin = (String) p.get("coin_id");
            String name = (String) p.get("name");
            LocalDate opened = LocalDate.parse((String) p.get("opened_day"));
            if (!day.isAfter(opened)) continue;
            var close = Returns.price(hist.get(coin), day);
            if (close.isEmpty()) continue;
            double px = close.get();
            double entry = ((Number) p.get("entry_price")).doubleValue();
            double qty = ((Number) p.get("qty")).doubleValue();
            double amount = ((Number) p.get("amount_usd")).doubleValue();
            Double risk = p.get("risk_usd") == null ? null : ((Number) p.get("risk_usd")).doubleValue();
            double tp = ((Number) p.get("take_profit")).doubleValue();
            double sl = ((Number) p.get("stop_loss")).doubleValue();
            String exit = null;
            if (px >= tp) exit = "止盈";
            else if (px <= sl) exit = sl >= entry ? "保本止损" : "止损";
            else if (ChronoUnit.DAYS.between(opened, day) >= MAX_DAYS) exit = "到期";
            if (exit != null) {
                double pnl = qty * px * (1 - COST_RATE) - amount;
                Double r = risk == null || risk <= 0 ? null : pnl / risk;
                jdbc.update("UPDATE sim_position SET status='closed', closed_day=?, exit_price=?, exit_reason=?, pnl_usd=?, pnl_pct=?,"
                        + " r_multiple=?, last_price=?, last_day=? WHERE id=?", day.toString(), px, exit, pnl, pnl / amount, r, px, day.toString(), id);
                ev.add(new Event(exit, coin, name, px, pnl, pnl / amount, "入场 " + fmt(entry) + " → " + fmt(px)
                        + (r == null ? "" : String.format(Locale.ROOT, "，%+.1fR", r))));
            } else {
                double newSl = sl;
                if (px >= entry * 1.5 && sl < entry) {
                    newSl = entry;
                    ev.add(new Event("保本", coin, name, px, null, null, "涨幅超过 50%，防守价上移到入场价 " + fmt(entry)));
                }
                jdbc.update("UPDATE sim_position SET last_price=?, last_day=?, stop_loss=? WHERE id=?", px, day.toString(), newSl, id);
            }
        }
        List<String> held = openCoinIds();
        int free = MAX_OPEN - held.size();
        for (Pick k : picks) {
            if (free <= 0) break;
            if (held.contains(k.coinId())) continue;
            Integer already = jdbc.queryForObject("SELECT COUNT(*) FROM sim_position WHERE coin_id=? AND opened_day=?",
                    Integer.class, k.coinId(), day.toString());
            if (already != null && already > 0) continue;
            Summary sm = summary();
            Sizing sz = size(sm.equity(), sm.cash(), k.ratio(), 1 - k.stopLoss() / k.price());
            if (sz.amount() < MIN_AMOUNT) {
                ev.add(new Event("跳过", k.coinId(), k.name(), k.price(), null, null, "可用现金不足，未建仓"));
                continue;
            }
            double qty = sz.amount() * (1 - COST_RATE) / k.price();
            jdbc.update("INSERT INTO sim_position(coin_id, name, path, opened_day, entry_price, amount_usd, qty, take_profit, stop_loss,"
                            + " ratio, reason, status, last_price, last_day, risk_usd, size_note) VALUES (?,?,?,?,?,?,?,?,?,?,?,'open',?,?,?,?)",
                    k.coinId(), k.name(), k.path(), day.toString(), k.price(), sz.amount(), qty, k.takeProfit(), k.stopLoss(),
                    k.ratio(), k.reason(), k.price(), day.toString(), sz.risk(), sz.note());
            ev.add(new Event("入场", k.coinId(), k.name(), k.price(), null, null,
                    String.format(Locale.ROOT, "金额 $%.0f（%s），目标 %s，防守 %s，盈亏比 %.1f", sz.amount(), sz.note(),
                            fmt(k.takeProfit()), fmt(k.stopLoss()), k.ratio())));
            held.add(k.coinId());
            free--;
        }
        return ev;
    }

    public record Sizing(double amount, double risk, String note) { }

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
        return new Sizing(amount, Math.min(risk, amount * downside), note);
    }

    public Summary summary() {
        double openValue = 0, unreal = 0, invested = 0;
        int open = 0;
        for (Map<String, Object> p : jdbc.queryForList("SELECT qty, last_price, amount_usd FROM sim_position WHERE status='open'")) {
            open++;
            double amt = ((Number) p.get("amount_usd")).doubleValue();
            double v = ((Number) p.get("qty")).doubleValue() * ((Number) p.get("last_price")).doubleValue() * (1 - COST_RATE);
            openValue += v;
            invested += amt;
            unreal += v - amt;
        }
        Map<String, Object> c = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(pnl_usd),0) s, COALESCE(SUM(CASE WHEN pnl_usd>0 THEN 1 ELSE 0 END),0) w"
                + " FROM sim_position WHERE status='closed'");
        int n = ((Number) c.get("n")).intValue();
        int w = ((Number) c.get("w")).intValue();
        double realized = ((Number) c.get("s")).doubleValue();
        double equity = START_EQUITY + realized;
        return new Summary(open, openValue, unreal, n, realized, w, n - w, equity, equity - invested, invested);
    }

    /** 全部记录导出为 CSV（data/sim/ledger.csv），方便复盘。 */
    public void exportCsv(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder s = new StringBuilder("﻿编号,币,路径,入场日,入场价,入场金额,数量,目标价,防守价,入场盈亏比,入场原因,状态,最新价,最新日,平仓日,平仓价,平仓原因,盈亏金额,盈亏比例,风险金额,R倍数,仓位说明\n");
        for (Map<String, Object> p : jdbc.queryForList("SELECT * FROM sim_position ORDER BY id")) {
            String[] cols = {"id", "name", "path", "opened_day", "entry_price", "amount_usd", "qty", "take_profit", "stop_loss", "ratio",
                    "reason", "status", "last_price", "last_day", "closed_day", "exit_price", "exit_reason", "pnl_usd", "pnl_pct",
                    "risk_usd", "r_multiple", "size_note"};
            List<String> v = new ArrayList<>();
            for (String col : cols) {
                Object o = p.get(col);
                String t = o == null ? "" : o.toString();
                v.add(t.contains(",") || t.contains("\"") ? "\"" + t.replace("\"", "\"\"") + "\"" : t);
            }
            s.append(String.join(",", v)).append("\n");
        }
        Files.writeString(file, s.toString(), StandardCharsets.UTF_8);
    }

    static String fmt(double p) {
        return com.phoenixscout.report.RiskReward.price(p);
    }
}
