package com.phoenixscout.sim;

import com.phoenixscout.market.CoinGeckoClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟仓实时盈亏：持仓按 CoinGecko 实时价（公共接口，2 分钟缓存）计算浮动盈亏、距目标/防守的距离和当前剩余盈亏比。
 * 实时价拿不到时退回最近收盘价并标明来源。止盈止损仍只按每日收盘价执行。
 */
public class LiveSimService {

    private final JdbcTemplate jdbc;
    private final CoinGeckoClient cg;

    public LiveSimService(JdbcTemplate jdbc, CoinGeckoClient cg) {
        this.jdbc = jdbc;
        this.cg = cg;
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> open = jdbc.queryForList("SELECT * FROM sim_position WHERE status='open' ORDER BY id");
        List<Map<String, Object>> closed = jdbc.queryForList("SELECT * FROM sim_position WHERE status='closed' ORDER BY closed_day DESC, id DESC");
        Map<String, CoinGeckoClient.LivePrice> live = Map.of();
        String liveError = null;
        try {
            live = cg.livePrices(open.stream().map(p -> (String) p.get("coin_id")).toList());
        } catch (Exception e) {
            liveError = "实时价获取失败，改用最近收盘价：" + e.getMessage();
        }
        List<Map<String, Object>> positions = new ArrayList<>();
        double invested = 0, value = 0, riskOpen = 0;
        for (var p : open) {
            String coin = (String) p.get("coin_id");
            double entry = num(p.get("entry_price")), qty = num(p.get("qty")), amount = num(p.get("amount_usd"));
            double tp = num(p.get("take_profit")), sl = num(p.get("stop_loss"));
            var lp = live.get(coin);
            double px = lp != null ? lp.price() : num(p.get("last_price"));
            double val = qty * px * (1 - SimLedger.COST_RATE);
            double pnl = val - amount;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coin", coin);
            m.put("name", p.get("name"));
            m.put("path", p.get("path"));
            m.put("openedDay", p.get("opened_day"));
            m.put("entryPrice", entry);
            m.put("amountUsd", round(amount));
            m.put("riskUsd", p.get("risk_usd") == null ? null : round(num(p.get("risk_usd"))));
            m.put("entryRatio", round(num(p.get("ratio"))));
            m.put("price", px);
            m.put("priceSource", lp != null ? "实时" : "收盘价 " + p.get("last_day"));
            m.put("priceTime", lp != null && lp.updatedAt() != null ? lp.updatedAt().toString() : null);
            m.put("valueUsd", round(val));
            m.put("pnlUsd", round(pnl));
            m.put("pnlPct", round(pnl / amount * 100));
            m.put("rMultiple", p.get("risk_usd") == null ? null : round(pnl / num(p.get("risk_usd"))));
            m.put("takeProfit", tp);
            m.put("stopLoss", sl);
            m.put("toTakeProfitPct", round((tp / px - 1) * 100));
            m.put("toStopLossPct", round((sl / px - 1) * 100));
            double up = tp / px - 1, down = 1 - sl / px;
            m.put("ratioNow", down <= 0 ? null : round(up / down));
            m.put("reason", p.get("reason"));
            positions.add(m);
            invested += amount;
            value += val;
            riskOpen += Math.max(0, qty * (px - sl));
        }
        double realized = closed.stream().mapToDouble(p -> num(p.get("pnl_usd"))).sum();
        long wins = closed.stream().filter(p -> num(p.get("pnl_usd")) > 0).count();
        double equity = SimLedger.START_EQUITY + realized;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("startEquity", SimLedger.START_EQUITY);
        totals.put("equityRealized", round(equity));
        totals.put("equityLive", round(equity + value - invested));
        totals.put("cash", round(equity - invested));
        totals.put("invested", round(invested));
        totals.put("openValue", round(value));
        totals.put("unrealizedUsd", round(value - invested));
        totals.put("realizedUsd", round(realized));
        totals.put("totalReturnPct", round((equity + value - invested) / SimLedger.START_EQUITY * 100 - 100));
        totals.put("openPositions", open.size());
        totals.put("closedTrades", closed.size());
        totals.put("wins", wins);
        totals.put("losses", closed.size() - wins);
        totals.put("winRatePct", closed.isEmpty() ? null : round(wins * 100.0 / closed.size()));
        totals.put("avgR", closed.isEmpty() ? null : round(closed.stream().filter(p -> p.get("r_multiple") != null)
                .mapToDouble(p -> num(p.get("r_multiple"))).average().orElse(0)));
        totals.put("riskToStopsUsd", round(riskOpen));
        List<Map<String, Object>> hist = new ArrayList<>();
        for (var p : closed) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coin", p.get("coin_id"));
            m.put("name", p.get("name"));
            m.put("path", p.get("path"));
            m.put("openedDay", p.get("opened_day"));
            m.put("closedDay", p.get("closed_day"));
            m.put("entryPrice", p.get("entry_price"));
            m.put("exitPrice", p.get("exit_price"));
            m.put("amountUsd", round(num(p.get("amount_usd"))));
            m.put("exitReason", p.get("exit_reason"));
            m.put("pnlUsd", round(num(p.get("pnl_usd"))));
            m.put("pnlPct", round(num(p.get("pnl_pct")) * 100));
            m.put("rMultiple", p.get("r_multiple") == null ? null : round(num(p.get("r_multiple"))));
            m.put("entryRatio", round(num(p.get("ratio"))));
            m.put("reason", p.get("reason"));
            hist.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", Instant.now().toString());
        out.put("note", "模拟仓只记账，不下真实订单；实时盈亏按 CoinGecko 实时价（2 分钟缓存），止盈止损只按每日 00:00 UTC 收盘价执行");
        if (liveError != null) out.put("warning", liveError);
        out.put("totals", totals);
        out.put("open", positions);
        out.put("closed", hist);
        return out;
    }

    static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
