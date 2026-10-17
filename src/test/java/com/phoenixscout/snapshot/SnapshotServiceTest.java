package com.phoenixscout.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class SnapshotServiceTest {

    static final String JSON = "{\"version\":\"v1\",\"dates\":[\"2025-10-31\",\"2026-07-31\"],"
            + "\"historical_top_k_ids\":[\"bitcoin\",\"foo\"],\"coins\":["
            + "{\"id\":\"bitcoin\",\"symbol\":\"btc\",\"name\":\"Bitcoin\",\"excluded_categories\":[],\"rank_2025_10_31\":1,"
            + "\"2025-10-31\":{\"price\":108409.0,\"price_flag\":\"ok\",\"market_cap\":2.0E12},"
            + "\"2026-07-31\":{\"price\":64743.0,\"price_flag\":\"ok\",\"market_cap\":1.3E12}},"
            + "{\"id\":\"foo\",\"symbol\":\"foo\",\"name\":\"Foo\",\"excluded_categories\":[\"stablecoins\"],\"status\":\"not_archived\"}]}";

    public static void write(Path dir, String version, String json, boolean goodSha) throws Exception {
        Files.createDirectories(dir);
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        Files.write(dir.resolve("snapshot_" + version + ".json"), b);
        String sha = goodSha ? SnapshotService.sha256(b) : "0".repeat(64);
        Files.writeString(dir.resolve("snapshot_" + version + ".json.sha256"), sha + "  snapshot_" + version + ".json\n");
    }

    @Test
    void loadsAndVerifies(@TempDir Path dir) throws Exception {
        write(dir, "v1", JSON, true);
        var st = new SnapshotService(dir).load();
        assertTrue(st.available(), st.message());
        var s = st.snapshot();
        assertEquals(LocalDate.of(2025, 10, 31), s.baseDate());
        assertEquals(108409.0, s.coins().get("bitcoin").points().get(s.baseDate()).price());
        assertEquals(java.util.List.of("stablecoins"), s.coins().get("foo").excludedCategories());
        assertTrue(s.coins().get("foo").points().isEmpty());
    }

    @Test
    void shaMismatchStopsUse(@TempDir Path dir) throws Exception {
        write(dir, "v1", JSON, false);
        var st = new SnapshotService(dir).load();
        assertFalse(st.available());
        assertTrue(st.message().contains("校验不符"));
    }

    @Test
    void missingSnapshotReported(@TempDir Path dir) {
        assertFalse(new SnapshotService(dir.resolve("none")).load().available());
    }

    @Test
    void highestVersionWins(@TempDir Path dir) throws Exception {
        write(dir, "v1", JSON, true);
        write(dir, "v2", JSON.replace("\"v1\"", "\"v2\""), true);
        assertEquals("v2", new SnapshotService(dir).load().snapshot().version());
    }
}
