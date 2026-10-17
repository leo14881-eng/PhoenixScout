package com.phoenixscout.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读历史快照（第六、七条）。启动和每次扫描前校验 SHA-256；
 * 文件缺失或校验不符 → 状态为不可用，依赖快照的计算（基准 A、路径 A1）停止并在报告中写明，绝不重建覆盖。
 */
public class SnapshotService {

    public record CoinAt(Double price, String priceFlag, Double marketCap) { }

    public record SnapshotCoin(String id, String symbol, String name, List<String> excludedCategories,
                               Integer rank20251031, Map<LocalDate, CoinAt> points) { }

    public record Snapshot(String version, String sha256, List<LocalDate> dates, List<String> historicalTopIds,
                           Map<String, SnapshotCoin> coins) {
        public LocalDate baseDate() {
            return dates.get(0);
        }

        public LocalDate midDate() {
            return dates.get(1);
        }
    }

    public record Status(boolean available, String message, Snapshot snapshot) { }

    private final Path dir;
    private final ObjectMapper om = new ObjectMapper();

    public SnapshotService(Path snapshotDir) {
        this.dir = snapshotDir;
    }

    /** 读取最高版本的快照并校验。 */
    public Status load() {
        Path json = latestVersionFile();
        if (json == null) return new Status(false, "快照文件不存在（data/snapshot/snapshot_vN.json）", null);
        Path shaFile = json.resolveSibling(json.getFileName() + ".sha256");
        try {
            byte[] data = Files.readAllBytes(json);
            String actual = sha256(data);
            if (!Files.exists(shaFile)) return new Status(false, "缺少校验文件 " + shaFile.getFileName(), null);
            String expected = Files.readString(shaFile, StandardCharsets.UTF_8).trim().split("\\s+")[0];
            if (!expected.equalsIgnoreCase(actual)) {
                return new Status(false, "快照校验不符：" + json.getFileName() + "（期望 " + expected.substring(0, 12)
                        + "… 实际 " + actual.substring(0, 12) + "…）", null);
            }
            return new Status(true, "快照 " + json.getFileName() + " 校验通过", parse(om.readTree(data), actual));
        } catch (IOException | RuntimeException e) {
            return new Status(false, "快照读取失败：" + e.getMessage(), null);
        }
    }

    Path latestVersionFile() {
        if (!Files.isDirectory(dir)) return null;
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("snapshot_v\\d+\\.json"))
                    .max((a, b) -> Integer.compare(ver(a), ver(b))).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static int ver(Path p) {
        String n = p.getFileName().toString();
        return Integer.parseInt(n.substring("snapshot_v".length(), n.length() - ".json".length()));
    }

    static Snapshot parse(JsonNode root, String sha) {
        List<LocalDate> dates = new ArrayList<>();
        root.get("dates").forEach(d -> dates.add(LocalDate.parse(d.asText())));
        List<String> top = new ArrayList<>();
        root.get("historical_top_k_ids").forEach(d -> top.add(d.asText()));
        Map<String, SnapshotCoin> coins = new LinkedHashMap<>();
        for (JsonNode c : root.get("coins")) {
            List<String> excl = new ArrayList<>();
            c.path("excluded_categories").forEach(e -> excl.add(e.asText()));
            Map<LocalDate, CoinAt> pts = new LinkedHashMap<>();
            for (LocalDate d : dates) {
                JsonNode p = c.get(d.toString());
                if (p == null || p.isNull()) continue;
                pts.put(d, new CoinAt(num(p.get("price")), p.path("price_flag").asText(), num(p.get("market_cap"))));
            }
            Integer rank = c.hasNonNull("rank_2025_10_31") ? c.get("rank_2025_10_31").asInt() : null;
            coins.put(c.get("id").asText(), new SnapshotCoin(c.get("id").asText(), c.path("symbol").asText(),
                    c.path("name").asText(), excl, rank, pts));
        }
        return new Snapshot(root.path("version").asText(), sha, dates, top, coins);
    }

    private static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
