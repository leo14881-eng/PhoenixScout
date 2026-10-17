package com.phoenixscout.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.phoenixscout.http.RateLimitedHttp;
import com.phoenixscout.market.CoinGeckoClient;
import com.phoenixscout.scan.DailyScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * P0 历史快照（第六、七条）的 Java 版，与 scripts/p0/archive.py、build_snapshot.py 产物格式一致，可互相续跑：
 * 归档当前前 N 个币的完整 365 天日线（原样保存接口返回 + 请求 URL/时间/状态），全部完成后生成只读 snapshot_v1。
 * 只在 2025-10-31 仍在 365 天窗口内时运行；已有快照则不再生成（修正只能出新版本）。
 */
public class P0ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(P0ArchiveService.class);
    public static final LocalDate BASE = LocalDate.of(2025, 10, 31);
    public static final LocalDate MID = LocalDate.of(2026, 7, 31);
    static final int TOP_K = 500;

    public interface Progress {
        void onFinished(String message);
    }

    private final Path raw;
    private final Path snapDir;
    private final CoinGeckoClient cg;
    private final int topN;
    private final ObjectMapper om = new ObjectMapper();
    private volatile String state = "idle";

    public P0ArchiveService(Path dataDir, CoinGeckoClient cg, int topN) {
        this.raw = dataDir.resolve("raw").resolve("p0-archive");
        this.snapDir = dataDir.resolve("snapshot");
        this.cg = cg;
        this.topN = topN;
    }

    public String state() {
        return state;
    }

    /** 2025-10-31 是否还在免费接口 365 天窗口内（留 1 天余量）。 */
    public static boolean windowOpen(LocalDate todayUtc) {
        return ChronoUnit.DAYS.between(BASE, todayUtc) <= 364;
    }

    public boolean snapshotExists() {
        try (var s = Files.list(snapDir)) {
            return s.anyMatch(p -> p.getFileName().toString().matches("snapshot_v\\d+\\.json"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 归档（可续跑）→ 生成快照。返回报告文字；不需要运行时返回 null。 */
    public String run(LocalDate todayUtc) throws InterruptedException, IOException {
        if (snapshotExists()) {
            state = "snapshot-exists";
            return null;
        }
        if (!windowOpen(todayUtc)) {
            state = "window-closed";
            return "⚠️ 2025-10-31 已超出免费接口 365 天窗口，无法再归档；未生成快照。基准 A 与路径 A1 将无法计算。";
        }
        Files.createDirectories(raw.resolve("history"));
        state = "universe";
        List<String[]> coins = universe();
        state = "history";
        int done = history(coins);
        if (done < coins.size()) done = history(coins);   // 失败的再补一轮（已归档的直接跳过）
        state = "categories";
        categories();
        state = "building";
        String report = buildSnapshot(coins.size(), done);
        state = "done";
        return report;
    }

    private RateLimitedHttp.Response get(String pathAndQuery) throws InterruptedException {
        return cg.rawGet(pathAndQuery);
    }

    List<String[]> universe() throws IOException, InterruptedException {
        Path f = raw.resolve("universe.json.gz");
        if (!Files.exists(f)) {
            ArrayNode coins = om.createArrayNode();
            for (int page = 1; page <= (topN + 249) / 250; page++) {
                var r = get("/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250&page=" + page);
                saveWrapped(raw.resolve("markets").resolve(String.format("page-%02d.json.gz", page)), r);
                if (!r.ok()) throw new IOException("行情第 " + page + " 页失败：" + r.status());
                for (JsonNode c : om.readTree(r.body())) {
                    ObjectNode o = coins.addObject();
                    o.put("id", c.path("id").asText());
                    o.put("symbol", c.path("symbol").asText());
                    o.put("name", c.path("name").asText());
                    o.set("rank_at_archive", c.get("market_cap_rank"));
                }
            }
            while (coins.size() > topN) coins.remove(coins.size() - 1);
            ObjectNode u = om.createObjectNode();
            u.put("created_at_utc", Instant.now().toString());
            u.put("top_n", topN);
            u.set("coins", coins);
            writeGz(f, om.writeValueAsBytes(u));
        }
        List<String[]> out = new ArrayList<>();
        for (JsonNode c : readGz(f).get("coins")) {
            out.add(new String[]{c.path("id").asText(), c.path("symbol").asText(), c.path("name").asText(),
                    c.hasNonNull("rank_at_archive") ? c.get("rank_at_archive").asText() : null});
        }
        return out;
    }

    int history(List<String[]> coins) throws IOException, InterruptedException {
        Path hd = raw.resolve("history");
        int done = 0, scanned = 0;
        for (String[] c : coins) {
            scanned++;
            Path f = hd.resolve(c[0] + ".json.gz");
            if (Files.exists(f)) {
                done++;
                continue;
            }
            var r = get("/coins/" + c[0] + "/market_chart?vs_currency=usd&days=365&interval=daily");
            if (r.ok()) {
                saveWrapped(f, r);
                done++;
            } else {
                ObjectNode fail = om.createObjectNode();
                fail.put("id", c[0]);
                fail.put("status", r.status());
                fail.put("detail", r.body() == null ? "" : r.body().substring(0, Math.min(300, r.body().length())));
                fail.put("requested_at_utc", r.requestedAt().toString());
                Files.writeString(raw.resolve("failures.jsonl"), om.writeValueAsString(fail) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            if (scanned % 25 == 0) writeProgress(done, coins.size(), scanned, false);
        }
        writeProgress(done, coins.size(), coins.size(), true);
        return done;
    }

    void categories() throws IOException, InterruptedException {
        Path d = raw.resolve("categories");
        Files.createDirectories(d);
        for (String cat : DailyScanService.EXCLUDE_CATEGORIES) {
            for (int page = 1; page <= 4; page++) {
                Path f = d.resolve(cat + "-p" + page + ".json.gz");
                if (Files.exists(f)) {
                    JsonNode prev = readGz(f);
                    if (prev.path("http_status").asInt() != 200 || prev.path("body").size() < 250) break;
                    continue;
                }
                var r = get("/coins/markets?vs_currency=usd&category=" + cat + "&per_page=250&page=" + page);
                saveWrapped(f, r);
                if (!r.ok() || om.readTree(r.body()).size() < 250) break;
            }
        }
    }

    /** 与 build_snapshot.py 相同的规则：只取精确 00:00 点；缺失即缺失；按 2025-10-31 市值重排取前 500。 */
    String buildSnapshot(int universeSize, int archived) throws IOException {
        Map<String, List<String>> excl = new HashMap<>();
        try (var s = Files.list(raw.resolve("categories"))) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches(".+-p\\d+\\.json\\.gz")).toList()) {
                String n = f.getFileName().toString();
                String cat = n.substring(0, n.lastIndexOf("-p"));
                JsonNode w = readGz(f);
                if (w.path("http_status").asInt() == 200) {
                    for (JsonNode c : om.readTree(w.path("body").isTextual() ? w.path("body").asText() : w.path("body").toString())) {
                        excl.computeIfAbsent(c.path("id").asText(), k -> new ArrayList<>()).add(cat);
                    }
                }
            }
        }
        JsonNode uni = readGz(raw.resolve("universe.json.gz"));
        ArrayNode rows = om.createArrayNode();
        List<ObjectNode> rankable = new ArrayList<>();
        int notArchived = 0, excluded = 0;
        Map<String, int[]> flags = new HashMap<>();   // date -> ok, mismatch, missing
        for (String d : List.of(BASE.toString(), MID.toString())) flags.put(d, new int[3]);
        for (JsonNode c : uni.get("coins")) {
            String id = c.path("id").asText();
            ObjectNode row = rows.addObject();
            row.put("id", id);
            row.put("symbol", c.path("symbol").asText());
            row.put("name", c.path("name").asText());
            row.set("rank_at_archive", c.get("rank_at_archive"));
            ArrayNode ex = row.putArray("excluded_categories");
            excl.getOrDefault(id, List.of()).forEach(ex::add);
            if (!ex.isEmpty()) excluded++;
            Path hf = raw.resolve("history").resolve(id + ".json.gz");
            if (!Files.exists(hf)) {
                notArchived++;
                row.put("status", "not_archived");
                continue;
            }
            JsonNode w = readGz(hf);
            JsonNode body = w.path("body").isTextual() ? om.readTree(w.path("body").asText()) : w.path("body");
            row.put("status", "archived");
            row.put("requested_at_utc", w.path("requested_at_utc").asText());
            for (String d : List.of(BASE.toString(), MID.toString())) {
                ObjectNode at = row.putObject(d);
                Object[] p = point(body.get("prices"), LocalDate.parse(d));
                Object[] m = point(body.get("market_caps"), LocalDate.parse(d));
                if (p[0] == null) at.putNull("price"); else at.put("price", (Double) p[0]);
                at.put("price_flag", (String) p[1]);
                if (m[0] == null) at.putNull("market_cap"); else at.put("market_cap", (Double) m[0]);
                at.put("market_cap_flag", (String) m[1]);
                int[] f = flags.get(d);
                f["ok".equals(p[1]) ? 0 : "timestamp_mismatch".equals(p[1]) ? 1 : 2]++;
            }
            JsonNode base = row.get(BASE.toString());
            if (ex.isEmpty() && "ok".equals(base.path("market_cap_flag").asText())
                    && base.path("market_cap").asDouble(0) > 0) rankable.add(row);
        }
        rankable.sort((a, b) -> Double.compare(b.get(BASE.toString()).get("market_cap").asDouble(),
                a.get(BASE.toString()).get("market_cap").asDouble()));
        ArrayNode top = om.createArrayNode();
        for (int i = 0; i < rankable.size(); i++) {
            rankable.get(i).put("rank_2025_10_31", i + 1);
            if (i < TOP_K) top.add(rankable.get(i).get("id").asText());
        }
        ObjectNode snap = om.createObjectNode();
        snap.put("version", "v1");
        snap.put("reason", "初版快照（服务内归档生成）");
        snap.put("created_at_utc", Instant.now().toString());
        snap.put("source", "CoinGecko 免费接口 /coins/{id}/market_chart days=365 interval=daily");
        snap.put("price_basis", "UTC 00:00 时间戳数据点（= 前一 UTC 日收盘价）");
        snap.putArray("dates").add(BASE.toString()).add(MID.toString());
        snap.put("universe_top_n", uni.path("top_n").asInt());
        snap.put("universe_created_at_utc", uni.path("created_at_utc").asText());
        ObjectNode st = snap.putObject("stats");
        st.put("universe", universeSize);
        st.put("archived", archived);
        st.put("not_archived", notArchived);
        st.put("excluded_by_category", excluded);
        st.put("rankable_2025_10_31", rankable.size());
        st.put("historical_top_k", top.size());
        snap.set("historical_top_k_ids", top);
        snap.putArray("known_limitations")
                .add("已退市/归零/改名的币无法从当前接口获取，历史成分存在残余幸存者偏差。")
                .add("初始名单为归档当时市值前 " + uni.path("top_n").asInt() + " 名；当时排名在此之外的币不在快照中。")
                .add("排除规则仅按 CoinGecko 分类执行；诈骗/操纵/停更需研究层判定。");
        snap.set("coins", rows);

        Files.createDirectories(snapDir);
        Path out = snapDir.resolve("snapshot_v1.json");
        if (Files.exists(out)) throw new IOException("拒绝覆盖已存在的 " + out);
        byte[] data = om.writerWithDefaultPrettyPrinter().writeValueAsBytes(snap);
        Path tmp = snapDir.resolve("snapshot_v1.json.tmp");
        Files.write(tmp, data);
        Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE);
        String sha = SnapshotService.sha256(data);
        Files.writeString(snapDir.resolve("snapshot_v1.json.sha256"), sha + "  snapshot_v1.json\n");
        out.toFile().setReadOnly();

        StringBuilder rep = new StringBuilder("✅ PhoenixScout 历史快照 snapshot_v1 已生成\n");
        rep.append("SHA-256：").append(sha, 0, 16).append("…\n");
        rep.append("初始名单 ").append(universeSize).append("｜成功归档 ").append(archived).append("｜未归档 ").append(notArchived)
                .append("｜按分类排除 ").append(excluded).append("\n");
        rep.append("2025-10-31 可排名 ").append(rankable.size()).append("｜历史成分 ").append(top.size()).append("/").append(TOP_K).append("\n");
        for (var e : flags.entrySet()) {
            rep.append(e.getKey()).append(" 价格 正常/时间戳不符/缺失：").append(e.getValue()[0]).append("/")
                    .append(e.getValue()[1]).append("/").append(e.getValue()[2]).append("\n");
        }
        if (top.size() < TOP_K) rep.append("注意：未达到前 ").append(TOP_K).append(" 名，不得宣称完成前 500 名快照。\n");
        Files.writeString(snapDir.resolve("snapshot_v1-覆盖率报告.md"), rep.toString(), StandardCharsets.UTF_8);
        return rep.toString();
    }

    static Object[] point(JsonNode series, LocalDate d) {
        long t = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        boolean near = false;
        if (series != null) {
            for (JsonNode p : series) {
                long ts = p.get(0).asLong();
                if (ts == t) {
                    return p.get(1).isNumber() ? new Object[]{p.get(1).asDouble(), "ok"} : new Object[]{null, "missing"};
                }
                if (Math.abs(ts - t) < 86_400_000L) near = true;
            }
        }
        return new Object[]{null, near ? "timestamp_mismatch" : "missing"};
    }

    private void writeProgress(int archived, int universe, int scanned, boolean finished) throws IOException {
        ObjectNode p = om.createObjectNode();
        p.put("updated_at_utc", Instant.now().toString());
        p.put("archived", archived);
        p.put("universe", universe);
        p.put("scanned", scanned);
        if (finished) p.put("finished", true);
        Files.writeString(raw.resolve("progress.json"), om.writeValueAsString(p));
        state = "history " + archived + "/" + universe;
    }

    private void saveWrapped(Path f, RateLimitedHttp.Response r) throws IOException {
        ObjectNode w = om.createObjectNode();
        w.put("source", "coingecko-free");
        w.put("request_url", r.url());
        w.put("requested_at_utc", r.requestedAt().toString());
        w.put("http_status", r.status());
        if (r.ok()) w.set("body", om.readTree(r.body()));
        else w.put("body", r.body() == null ? "" : r.body().substring(0, Math.min(500, r.body().length())));
        writeGz(f, om.writeValueAsBytes(w));
    }

    private static void writeGz(Path f, byte[] data) throws IOException {
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            o.write(data);
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private JsonNode readGz(Path f) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(f))) {
            return om.readTree(in);
        }
    }
}
