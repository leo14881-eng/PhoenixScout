package com.phoenixscout.store;

import com.phoenixscout.market.CoinGeckoClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/** CoinGecko Demo key 每月调用计数，存在 kv 表（键 cg-key-usage:yyyy-MM）。 */
public class KvUsageMeter implements CoinGeckoClient.UsageMeter {

    private final JdbcTemplate jdbc;

    public KvUsageMeter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int used(String month) {
        List<String> v = jdbc.queryForList("SELECT v FROM kv WHERE k = ?", String.class, "cg-key-usage:" + month);
        return v.isEmpty() ? 0 : Integer.parseInt(v.get(0));
    }

    @Override
    public synchronized void add(String month) {
        jdbc.update("INSERT INTO kv(k, v) VALUES (?, '1') ON CONFLICT(k) DO UPDATE SET v = CAST(v AS INTEGER) + 1",
                "cg-key-usage:" + month);
    }
}
