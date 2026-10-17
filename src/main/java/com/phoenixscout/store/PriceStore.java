package com.phoenixscout.store;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.market.MarketCoin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** 日线与行情快照的存取。批量写入用一个事务，读取按币或按日期批量取，避免逐条查询。 */
public class PriceStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public PriceStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void saveDaily(String coinId, Collection<DailyPoint> points, String source, Instant requestedAt) {
        if (points.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (DailyPoint p : points) {
            rows.add(new Object[]{coinId, p.day().toString(), p.price(), p.marketCap(), p.volume(), source,
                    requestedAt.toString()});
        }
        tx.executeWithoutResult(s -> jdbc.batchUpdate(
                "INSERT OR REPLACE INTO price_daily(coin_id, day, price, market_cap, volume, source, requested_at)"
                        + " VALUES (?,?,?,?,?,?,?)", rows));
    }

    public TreeMap<LocalDate, DailyPoint> history(String coinId) {
        TreeMap<LocalDate, DailyPoint> out = new TreeMap<>();
        jdbc.query("SELECT day, price, market_cap, volume FROM price_daily WHERE coin_id = ? ORDER BY day", rs -> {
            LocalDate d = LocalDate.parse(rs.getString(1));
            out.put(d, new DailyPoint(d, dbl(rs, 2), dbl(rs, 3), dbl(rs, 4)));
        }, coinId);
        return out;
    }

    /** 一次取出多个币的完整历史（扫描时用，避免 N 次查询）。 */
    public Map<String, TreeMap<LocalDate, DailyPoint>> histories(Collection<String> coinIds) {
        Map<String, TreeMap<LocalDate, DailyPoint>> out = new HashMap<>();
        List<String> ids = new ArrayList<>(coinIds);
        for (int i = 0; i < ids.size(); i += 500) {
            List<String> part = ids.subList(i, Math.min(ids.size(), i + 500));
            String in = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            jdbc.query("SELECT coin_id, day, price, market_cap, volume FROM price_daily WHERE coin_id IN (" + in
                    + ") ORDER BY coin_id, day", rs -> {
                LocalDate d = LocalDate.parse(rs.getString(2));
                out.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                        .put(d, new DailyPoint(d, dbl(rs, 3), dbl(rs, 4), dbl(rs, 5)));
            }, part.toArray());
        }
        return out;
    }

    public Map<String, LocalDate> latestDays() {
        Map<String, LocalDate> out = new HashMap<>();
        jdbc.query("SELECT coin_id, MAX(day) FROM price_daily GROUP BY coin_id",
                rs -> { out.put(rs.getString(1), LocalDate.parse(rs.getString(2))); });
        return out;
    }

    public long dailyCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM price_daily", Long.class);
        return n == null ? 0 : n;
    }

    public void saveMarketSnapshot(LocalDate scanDay, List<MarketCoin> coins, Instant requestedAt) {
        List<Object[]> rows = new ArrayList<>();
        for (MarketCoin c : coins) {
            rows.add(new Object[]{scanDay.toString(), c.id(), c.symbol(), c.name(), c.rank(), c.price(), c.marketCap(),
                    c.fdv(), c.circulating(), c.totalSupply(), c.maxSupply(), c.volume24h(), requestedAt.toString()});
        }
        tx.executeWithoutResult(s -> jdbc.batchUpdate("INSERT OR REPLACE INTO market_snapshot(scan_day, coin_id, symbol,"
                + " name, rank, price, market_cap, fdv, circulating, total_supply, max_supply, volume_24h, requested_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", rows));
    }

    public Optional<LocalDate> previousScanDay(LocalDate before) {
        List<String> r = jdbc.queryForList("SELECT MAX(scan_day) FROM scan_run WHERE scan_day < ? AND status IN ('ok','partial')",
                String.class, before.toString());
        return r.isEmpty() || r.get(0) == null ? Optional.empty() : Optional.of(LocalDate.parse(r.get(0)));
    }

    private static Double dbl(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double v = rs.getDouble(i);
        return rs.wasNull() ? null : v;
    }
}
