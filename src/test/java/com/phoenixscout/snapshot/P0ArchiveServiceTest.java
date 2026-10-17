package com.phoenixscout.snapshot;

import com.phoenixscout.http.RateLimitedHttp;
import com.phoenixscout.market.CoinGeckoClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class P0ArchiveServiceTest {

    static long ms(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    static String chart(double base, double mid, double cap) {
        return "{\"prices\":[[" + ms(P0ArchiveService.BASE) + "," + base + "],[" + ms(P0ArchiveService.MID) + "," + mid + "]],"
                + "\"market_caps\":[[" + ms(P0ArchiveService.BASE) + "," + cap + "],[" + ms(P0ArchiveService.MID) + "," + cap / 2 + "]],"
                + "\"total_volumes\":[]}";
    }

    @Test
    void archivesBuildsAndVerifiesSnapshot(@TempDir Path data) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var http = new RateLimitedHttp("t", 0, 2, (u, h) -> {
            calls.incrementAndGet();
            String body;
            if (u.contains("/market_chart")) {
                if (u.contains("/coins/small/")) body = chart(1, 0.5, 1e6);
                else if (u.contains("/coins/big/")) body = chart(10, 5, 1e9);
                else if (u.contains("/coins/usdx/")) body = chart(1, 1, 5e9);
                else return new RateLimitedHttp.Response(404, "", u, Instant.now());
            } else if (u.contains("category=stablecoins") && u.endsWith("page=1")) {
                body = "[{\"id\":\"usdx\"}]";
            } else if (u.contains("category=")) {
                body = "[]";
            } else if (u.endsWith("page=1")) {
                body = "[{\"id\":\"big\",\"symbol\":\"b\",\"name\":\"Big\",\"market_cap_rank\":1},"
                        + "{\"id\":\"usdx\",\"symbol\":\"u\",\"name\":\"USDX\",\"market_cap_rank\":2},"
                        + "{\"id\":\"small\",\"symbol\":\"s\",\"name\":\"Small\",\"market_cap_rank\":3},"
                        + "{\"id\":\"gone\",\"symbol\":\"g\",\"name\":\"Gone\",\"market_cap_rank\":4}]";
            } else {
                body = "[]";
            }
            return new RateLimitedHttp.Response(200, body, u, Instant.now());
        }, ms -> { });
        var cg = new CoinGeckoClient("http://cg", http, null, null);
        var svc = new P0ArchiveService(data, cg, 250);
        String report = svc.run(LocalDate.of(2026, 10, 17));
        assertNotNull(report);
        assertTrue(report.contains("成功归档 3"), report);
        assertTrue(report.contains("未归档 1"));

        var st = new SnapshotService(data.resolve("snapshot")).load();
        assertTrue(st.available(), st.message());
        var s = st.snapshot();
        assertEquals(List.of("big", "small"), s.historicalTopIds(), "稳定币排除，按 2025-10-31 市值排序");
        assertEquals(10.0, s.coins().get("big").points().get(P0ArchiveService.BASE).price());
        assertEquals(List.of("stablecoins"), s.coins().get("usdx").excludedCategories());
        assertTrue(Files.exists(data.resolve("raw/p0-archive/failures.jsonl")));

        // 已有快照：不再请求、不覆盖
        int before = calls.get();
        assertNull(svc.run(LocalDate.of(2026, 10, 17)));
        assertEquals(before, calls.get());
    }

    @Test
    void windowClosedAfter365Days() {
        assertTrue(P0ArchiveService.windowOpen(LocalDate.of(2026, 10, 30)));
        assertFalse(P0ArchiveService.windowOpen(LocalDate.of(2026, 10, 31)));
    }

    @Test
    void pointFlags() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var series = om.readTree("[[" + (ms(P0ArchiveService.BASE) + 3600000) + ",5]]");
        assertEquals("timestamp_mismatch", P0ArchiveService.point(series, P0ArchiveService.BASE)[1]);
        assertEquals("missing", P0ArchiveService.point(series, P0ArchiveService.MID)[1]);
    }
}
