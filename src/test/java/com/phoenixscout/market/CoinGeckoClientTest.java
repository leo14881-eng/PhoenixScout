package com.phoenixscout.market;

import com.phoenixscout.http.RateLimitedHttp;
import com.phoenixscout.store.SqliteApiCache;
import com.phoenixscout.support.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class CoinGeckoClientTest {

    static final long D = 86_400_000L;
    static final long T0 = LocalDate.of(2026, 10, 15).toEpochDay() * D;

    static String chartJson() {
        return "{\"prices\":[[" + T0 + ",10.0],[" + (T0 + D) + ",11.0],[" + (T0 + D + 3_600_000) + ",12.0]],"
                + "\"market_caps\":[[" + T0 + ",1000],[" + (T0 + D) + ",1100]],"
                + "\"total_volumes\":[[" + T0 + ",5],[" + (T0 + D) + ",6]]}";
    }

    @Test
    void keepsOnlyMidnightPoints() throws Exception {
        var http = new RateLimitedHttp("t", 0, 2, (u, h) -> new RateLimitedHttp.Response(200, chartJson(), u, Instant.now()), ms -> { });
        var c = new CoinGeckoClient("http://x", http, null, null);
        var pts = c.marketChart("abc", 2);
        assertEquals(2, pts.size(), "非整点的当前价必须丢弃");
        assertEquals(11.0, pts.get(LocalDate.of(2026, 10, 16)).price());
        assertEquals(1100.0, pts.get(LocalDate.of(2026, 10, 16)).marketCap());
    }

    @Test
    void cacheAvoidsRepeatRequestsAnd404IsRemembered(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        int[] calls = {0};
        var http = new RateLimitedHttp("t", 0, 2, (u, h) -> {
            calls[0]++;
            return u.contains("gone") ? new RateLimitedHttp.Response(404, "", u, Instant.now())
                    : new RateLimitedHttp.Response(200, chartJson(), u, Instant.now());
        }, ms -> { });
        var c = new CoinGeckoClient("http://x", http, null, new SqliteApiCache(db.jdbc));
        c.marketChart("abc", 2);
        c.marketChart("abc", 2);
        assertEquals(1, calls[0], "TTL 内第二次走缓存");
        assertThrows(ApiException.class, () -> c.marketChart("gone", 2));
        assertThrows(ApiException.class, () -> c.marketChart("gone", 2));
        assertEquals(2, calls[0], "404 的币 7 天内不再请求");
    }

    @Test
    void archiveReusesScanResponseWithOriginalTime(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        int[] calls = {0};
        var http = new RateLimitedHttp("t", 0, 2, (u, h) -> {
            calls[0]++;
            return new RateLimitedHttp.Response(200, chartJson(), u, Instant.now());
        }, ms -> { });
        var c = new CoinGeckoClient("http://x", http, null, new SqliteApiCache(db.jdbc));
        c.marketChart("abc", 365);
        var r = c.rawGet("/coins/abc/market_chart?vs_currency=usd&days=365&interval=daily");
        assertEquals(1, calls[0], "归档复用扫描已取过的同一请求");
        assertTrue(r.ok());
        assertTrue(r.requestedAt().isBefore(Instant.now().plusSeconds(1)));
        c.rawGet("/coins/zzz/market_chart?vs_currency=usd&days=365&interval=daily");
        c.marketChart("zzz", 365);
        assertEquals(2, calls[0], "扫描也复用归档新取的结果");
    }

    @Test
    void keyBudgetFallsBackToPublic(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("k");
        Files.writeString(key, "KEY");
        var db = new TestDb(dir);
        var meter = new com.phoenixscout.store.KvUsageMeter(db.jdbc);
        String month = java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
        java.util.List<String> keys = new java.util.ArrayList<>();
        var http = new RateLimitedHttp("t", 7000, 2, (u, h) -> {
            keys.add(h.get("x-cg-demo-api-key"));
            return new RateLimitedHttp.Response(200, "[]", u, Instant.now());
        }, ms -> { });
        var c = new CoinGeckoClient("http://x", http, key, null);
        c.setUsageMeter(meter);
        c.marketsPage(1, null);
        assertEquals("KEY", keys.get(0));
        assertEquals(1, meter.used(month));
        db.jdbc.update("UPDATE kv SET v = ? WHERE k = ?", String.valueOf(CoinGeckoClient.MONTHLY_KEY_BUDGET), "cg-key-usage:" + month);
        c.marketsPage(2, null);
        assertNull(keys.get(1), "额度用完后不再带 key");
        assertEquals(7000, http.minIntervalMs());
    }

    @Test
    void demoKeyIsSentAndSpeedsUp(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("k");
        Files.writeString(key, "KEY123\n");
        String[] seen = {null};
        var http = new RateLimitedHttp("t", 7000, 2, (u, h) -> {
            seen[0] = h.get("x-cg-demo-api-key");
            return new RateLimitedHttp.Response(200, "[]", u, Instant.now());
        }, ms -> { });
        var c = new CoinGeckoClient("http://x", http, key, null);
        assertTrue(c.usingDemoKey());
        assertEquals(2200, http.minIntervalMs());
        c.marketsPage(1, null);
        assertEquals("KEY123", seen[0]);
        Files.delete(key);
        c.refreshKey(true);
        assertFalse(c.usingDemoKey(), "删掉 key 文件后退回公共接口");
        assertEquals(7000, http.minIntervalMs());
    }
}
