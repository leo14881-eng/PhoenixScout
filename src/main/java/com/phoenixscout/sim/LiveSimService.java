package com.phoenixscout.sim;

import com.phoenixscout.market.CoinGeckoClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模拟台账实时盈亏（/api/sim、状态页）：持仓按 CoinGecko 实时价（公共接口、不占 Demo key 额度、2 分钟缓存）计算。
 * 实时价拿不到时用最近收盘价并标明。止盈止损只按每日 00:00 UTC 收盘价执行，实时价只用于查看。
 */
public class LiveSimService {

    private final JdbcTemplate jdbc;
    private final CoinGeckoClient cg;
    private final SimReview review;

    public LiveSimService(JdbcTemplate jdbc, CoinGeckoClient cg) {
        this.jdbc = jdbc;
        this.cg = cg;
        this.review = new SimReview(jdbc);
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> open = jdbc.queryForList("SELECT * FROM sim_trade WHERE status='open' ORDER BY book DESC, id");
        Set<String> ids = new LinkedHashSet<>();
        open.forEach(p -> ids.add((String) p.get("coin_id")));
        Map<String, CoinGeckoClient.LivePrice> live = Map.of();
        String warn = null;
        try {
            live = cg.livePrices(ids);
        } catch (Exception e) {
            warn = "实时价获取失败，改用最近收盘价：" + e.getMessage();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", Instant.now().toString());
        out.put("note", "只记账，不下真实订单。实时盈亏按 CoinGecko 实时价（2 分钟缓存）；止盈止损只按每日 00:00 UTC 收盘价执行。");
        if (warn != null) out.put("warning", warn);
        for (String book : List.of(SimLedger.PORTFOLIO, SimLedger.TRACK)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            double invested = 0, value = 0, partial = 0;
            for (var p : open) {
                if (!book.equals(p.get("book"))) continue;
                String coin = (String) p.get("coin_id");
                double entry = d(p.get("entry_price")), amount = d(p.get("amount_usd")), qty = d(p.get("qty")), qty0 = d(p.get("qty_initial"));
                double risk = d(p.get("risk_usd")), stop = d(p.get("stop_loss")), realized = d(p.get("realized_usd"));
                Double tp1 = p.get("tp1") == null ? null : d(p.get("tp1"));
                var lp = live.get(coin);
                double px = lp != null ? lp.price() : d(p.get("last_price"));
                double val = qty * px * (1 - SimLedger.COST_RATE);
                double cost = amount * qty / qty0;
                double pnl = val - cost + realized;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("coin", coin);
                m.put("name", p.get("name"));
                m.put("path", p.get("path"));
                m.put("grade", p.get("grade"));
                m.put("quality", p.get("quality"));
                m.put("openedDay", p.get("opened_day"));
                m.put("entryPrice", entry);
                m.put("amountUsd", r(amount));
                m.put("riskUsd", r(risk));
                m.put("price", px);
                m.put("priceSource", lp != null ? "实时" : "收盘价 " + p.get("last_day"));
                m.put("pnlUsd", r(pnl));
                m.put("pnlPct", r(pnl / amount * 100));
                m.put("rMultiple", risk > 0 ? r(pnl / risk) : null);
                m.put("halfSold", ((Number) p.get("tp1_done")).intValue() == 1);
                m.put("tp1", tp1);
                m.put("stopLoss", stop);
                m.put("toTp1Pct", tp1 == null ? null : r((tp1 / px - 1) * 100));
                m.put("toStopPct", r((stop / px - 1) * 100));
                m.put("entryEv", p.get("ev") == null ? null : r(d(p.get("ev")) * 100));
                m.put("reason", p.get("reason"));
                m.put("sizeNote", p.get("size_note"));
                rows.add(m);
                invested += cost;
                value += val;
                partial += realized;
            }
            Map<String, Object> closed = jdbc.queryForMap("SELECT COUNT(*) n, COALESCE(SUM(pnl_usd),0) s FROM sim_trade WHERE book=? AND status='closed'", book);
            double realizedClosed = d(closed.get("s"));
            double unreal = value - invested;
            Map<String, Object> b = new LinkedHashMap<>();
            if (book.equals(SimLedger.PORTFOLIO)) {
                double equity = SimLedger.START_EQUITY + realizedClosed + partial + unreal;
                b.put("startEquity", SimLedger.START_EQUITY);
                b.put("equityLive", r(equity));
                b.put("returnPct", r(equity / SimLedger.START_EQUITY * 100 - 100));
                b.put("cash", r(SimLedger.START_EQUITY + realizedClosed + partial - invested));
            }
            b.put("open", rows.size());
            b.put("invested", r(invested));
            b.put("unrealizedUsd", r(unreal + partial));
            b.put("realizedClosedUsd", r(realizedClosed));
            b.put("closedTrades", ((Number) closed.get("n")).intValue());
            b.put("review", review.stats(book));
            b.put("positions", rows);
            out.put(book.equals(SimLedger.PORTFOLIO) ? "portfolio" : "track", b);
        }
        out.put("recentClosed", jdbc.queryForList("SELECT book, name, path, grade, opened_day, closed_day, entry_price, exit_price, amount_usd, exit_reason,"
                + " round(pnl_usd,2) pnl_usd, round(pnl_pct*100,1) pnl_pct, round(r_multiple,2) r_multiple, round(ev*100,1) ev_pct, reason FROM sim_trade"
                + " WHERE status='closed' ORDER BY closed_day DESC, id DESC LIMIT 50"));
        return out;
    }

    static double d(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    static double r(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
