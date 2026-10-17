package com.phoenixscout.store;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.support.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PriceStoreTest {

    @Test
    void saveReadAndMissingStaysMissing(@TempDir Path dir) {
        var db = new TestDb(dir);
        var ps = new PriceStore(db.jdbc, db.tx);
        LocalDate d = LocalDate.of(2026, 1, 1);
        ps.saveDaily("a", List.of(new DailyPoint(d, 1.5, null, 3.0), new DailyPoint(d.plusDays(1), 2.0, 9.0, null)),
                "test", Instant.now());
        var h = ps.history("a");
        assertEquals(2, h.size());
        assertNull(h.get(d).marketCap(), "缺失不得变成 0");
        assertEquals(d.plusDays(1), ps.latestDays().get("a"));
        assertEquals(1, ps.histories(List.of("a", "b")).size());
    }

    @Test
    void cacheTtlAndPurge(@TempDir Path dir) {
        var db = new TestDb(dir);
        var c = new SqliteApiCache(db.jdbc);
        c.put("k", "中文 body");
        assertEquals("中文 body", c.get("k", Duration.ofMinutes(1)).orElseThrow());
        assertEquals("中文 body", new SqliteApiCache(db.jdbc).get("k", Duration.ofMinutes(1)).orElseThrow(), "重启后仍有效");
        assertTrue(new SqliteApiCache(db.jdbc).get("k", Duration.ZERO.minusMillis(1)).isEmpty(), "过期不返回");
        assertEquals(0, c.purgeOlderThan(Duration.ofDays(1)));
    }

    @Test
    void importsArchiveOnce(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        var ps = new PriceStore(db.jdbc, db.tx);
        Path hist = Files.createDirectories(dir.resolve("history"));
        long ts = LocalDate.of(2025, 10, 31).toEpochDay() * 86_400_000L;
        String json = "{\"http_status\":200,\"requested_at_utc\":\"2026-10-16T23:33:04+00:00\",\"body\":{\"prices\":[["
                + ts + ",108409.0],[" + (ts + 5) + ",1]],\"market_caps\":[[" + ts + ",2.0]],\"total_volumes\":[]}}";
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(hist.resolve("bitcoin.json.gz")))) {
            o.write(json.getBytes(StandardCharsets.UTF_8));
        }
        var imp = new ArchiveImporter(db.jdbc, ps);
        assertEquals(1, imp.importAll(hist));
        assertEquals(0, imp.importAll(hist), "同一文件只导入一次");
        var h = ps.history("bitcoin");
        assertEquals(1, h.size());
        assertEquals(108409.0, h.firstEntry().getValue().price());
    }
}
