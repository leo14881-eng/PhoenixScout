package com.phoenixscout.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.market.CoinGeckoClientParser;
import com.phoenixscout.market.DailyPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * 把 P0 归档（data/raw/p0-archive/history/*.json.gz）导入 price_daily，作为历史日线的种子：
 * 之后每日只需增量补 1–2 天，不必再请求 365 天。每个文件按“文件名+大小”只导入一次。
 */
public class ArchiveImporter {

    private static final Logger log = LoggerFactory.getLogger(ArchiveImporter.class);
    private final JdbcTemplate jdbc;
    private final PriceStore prices;
    private final ObjectMapper om = new ObjectMapper();

    public ArchiveImporter(JdbcTemplate jdbc, PriceStore prices) {
        this.jdbc = jdbc;
        this.prices = prices;
    }

    public int importAll(Path historyDir) {
        if (!Files.isDirectory(historyDir)) return 0;
        int n = 0;
        try (Stream<Path> files = Files.list(historyDir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json.gz")).sorted().toList()) {
                if (importOne(f)) n++;
            }
        } catch (IOException e) {
            log.warn("读取归档目录失败：{}", e.getMessage());
        }
        if (n > 0) log.info("已从 P0 归档导入 {} 个币的历史日线", n);
        return n;
    }

    boolean importOne(Path f) {
        String name = f.getFileName().toString();
        String coinId = name.substring(0, name.length() - ".json.gz".length());
        String marker;
        try {
            marker = "p0import:" + name + ":" + Files.size(f);
        } catch (IOException e) {
            return false;
        }
        List<String> done = jdbc.queryForList("SELECT v FROM kv WHERE k = ?", String.class, marker);
        if (!done.isEmpty()) return false;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
            JsonNode w = om.readTree(in);
            if (w.path("http_status").asInt() != 200) return false;
            TreeMap<java.time.LocalDate, DailyPoint> pts = CoinGeckoClientParser.parse(w.get("body"));
            Instant at = Instant.parse(w.path("requested_at_utc").asText().replace("+00:00", "Z"));
            prices.saveDaily(coinId, pts.values(), "p0-archive", at);
            jdbc.update("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", marker, Instant.now().toString());
            return true;
        } catch (Exception e) {
            log.warn("导入 {} 失败：{}", name, e.getMessage());
            return false;
        }
    }
}
