package com.phoenixscout.store;

import com.phoenixscout.http.ApiCache;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 两级缓存：进程内 LRU（最近 512 条）+ SQLite（gzip）。重启后 SQLite 里的仍然有效。 */
public class SqliteApiCache implements ApiCache {

    private record Entry(String body, long fetchedAt) { }

    private final JdbcTemplate jdbc;
    private final Map<String, Entry> memory = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > 512;
        }
    };

    public SqliteApiCache(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public synchronized Optional<String> get(String key, Duration ttl) {
        long cutoff = System.currentTimeMillis() - ttl.toMillis();
        Entry e = memory.get(key);
        if (e != null && e.fetchedAt >= cutoff) return Optional.of(e.body);
        List<Entry> rows = jdbc.query("SELECT body_gz, fetched_at FROM api_cache WHERE cache_key = ?",
                (rs, i) -> new Entry(gunzip(rs.getBytes(1)), rs.getLong(2)), key);
        if (rows.isEmpty()) return Optional.empty();
        Entry row = rows.get(0);
        memory.put(key, row);
        return row.fetchedAt >= cutoff ? Optional.of(row.body) : Optional.empty();
    }

    @Override
    public synchronized Optional<Map.Entry<String, java.time.Instant>> getWithTime(String key, Duration ttl) {
        if (get(key, ttl).isEmpty()) return Optional.empty();
        Entry e = memory.get(key);
        return e == null ? Optional.empty() : Optional.of(Map.entry(e.body, java.time.Instant.ofEpochMilli(e.fetchedAt)));
    }

    @Override
    public synchronized void put(String key, String body) {
        long now = System.currentTimeMillis();
        memory.put(key, new Entry(body, now));
        jdbc.update("INSERT OR REPLACE INTO api_cache(cache_key, body_gz, fetched_at) VALUES (?,?,?)",
                key, gzip(body), now);
    }

    @Override
    public Optional<Integer> negative(String key, Duration ttl) {
        List<Integer> r = jdbc.query("SELECT status FROM api_negative WHERE cache_key = ? AND fetched_at >= ?",
                (rs, i) -> rs.getInt(1), key, System.currentTimeMillis() - ttl.toMillis());
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    @Override
    public void putNegative(String key, int status) {
        jdbc.update("INSERT OR REPLACE INTO api_negative(cache_key, status, fetched_at) VALUES (?,?,?)",
                key, status, System.currentTimeMillis());
    }

    /** 删除超过 maxAge 的缓存，控制数据库体积。 */
    public int purgeOlderThan(Duration maxAge) {
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        synchronized (this) {
            memory.entrySet().removeIf(e -> e.getValue().fetchedAt < cutoff);
        }
        return jdbc.update("DELETE FROM api_cache WHERE fetched_at < ?", cutoff);
    }

    static byte[] gzip(String s) {
        try (var bos = new ByteArrayOutputStream(); var gz = new GZIPOutputStream(bos)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
            gz.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String gunzip(byte[] b) {
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(b))) {
            return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
