package com.phoenixscout.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.http.ApiCache;
import com.phoenixscout.http.RateLimitedHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * CoinGecko 免费接口（公共接口无 key；可选免费 Demo key）。
 * 尽量用批量接口（/coins/markets 每次 250 个币），所有响应先查缓存，TTL 内不重复请求；
 * 404 的币 7 天内不再请求。
 */
public class CoinGeckoClient {

    public static final Duration TTL_MARKETS = Duration.ofMinutes(30);
    public static final Duration TTL_CATEGORY = Duration.ofDays(7);
    public static final Duration TTL_CHART = Duration.ofHours(20);
    public static final Duration TTL_NEGATIVE = Duration.ofDays(7);

    private final String baseUrl;
    private final RateLimitedHttp http;
    private final Map<String, String> headers = new java.util.concurrent.ConcurrentHashMap<>();
    private final ObjectMapper om = new ObjectMapper();
    private final ApiCache cache;

    public CoinGeckoClient(String baseUrl, RateLimitedHttp http, Path demoKeyFile, ApiCache cache) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.cache = cache == null ? ApiCache.NONE : cache;
        this.demoKeyFile = demoKeyFile;
        this.publicIntervalMs = http.minIntervalMs();
        refreshKey(true);
    }

    /** Demo key 每月额度（官方 10,000 次）；本月用到上限就改用公共接口，不会超额。 */
    public static final int MONTHLY_KEY_BUDGET = 9_000;

    /** 每月 key 调用次数计数（SQLite 持久化，重启不丢）。 */
    public interface UsageMeter {
        int used(String month);

        void add(String month);
    }

    private UsageMeter meter;

    public void setUsageMeter(UsageMeter meter) {
        this.meter = meter;
    }

    private RateLimitedHttp.Response send(String pathAndQuery) throws InterruptedException {
        String month = java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString();
        boolean withKey = headers.containsKey("x-cg-demo-api-key") && (meter == null || meter.used(month) < MONTHLY_KEY_BUDGET);
        if (!withKey && headers.containsKey("x-cg-demo-api-key")) {
            http.setMinIntervalMs(publicIntervalMs);   // 本月额度用完：按公共接口限速
            return http.get(baseUrl + pathAndQuery, Map.of());
        }
        RateLimitedHttp.Response r = http.get(baseUrl + pathAndQuery, headers);
        if (withKey && meter != null) meter.add(month);
        return r;
    }

    public int keyUsedThisMonth() {
        return meter == null ? 0 : meter.used(java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString());
    }

    private final Path demoKeyFile;
    private final long publicIntervalMs;
    private long keyCheckedAt;

    /** 每分钟检查一次 key 文件：放进去就自动提速，删掉就退回公共接口，不需要重启。 */
    synchronized void refreshKey(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - keyCheckedAt < 60_000) return;
        keyCheckedAt = now;
        String key = null;
        if (demoKeyFile != null && Files.isRegularFile(demoKeyFile)) {
            try {
                key = Files.readString(demoKeyFile, StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
                // 读不到就用公共接口
            }
        }
        if (key != null && !key.isEmpty()) {
            headers.put("x-cg-demo-api-key", key);
            http.setMinIntervalMs(Math.min(publicIntervalMs, 2200));
        } else {
            headers.remove("x-cg-demo-api-key");
            http.setMinIntervalMs(publicIntervalMs);
        }
    }

    public boolean usingDemoKey() {
        return headers.containsKey("x-cg-demo-api-key");
    }

    public RateLimitedHttp http() {
        return http;
    }

    public record LivePrice(double price, java.time.Instant updatedAt) { }

    /**
     * 实时价（批量，一次最多 250 个币）：走公共接口、不带 key，不占 Demo key 的月额度；2 分钟缓存。
     * 只用于实时盈亏展示，不用于任何判定（判定一律用 00:00 UTC 收盘价）。
     */
    public Map<String, LivePrice> livePrices(java.util.Collection<String> ids) throws ApiException, InterruptedException {
        Map<String, LivePrice> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        String path = "/simple/price?vs_currencies=usd&include_last_updated_at=true&ids=" + String.join(",", new java.util.TreeSet<>(ids));
        String key = "cg-live:" + path;
        String body = cache.get(key, Duration.ofMinutes(2)).orElse(null);
        if (body == null) {
            RateLimitedHttp.Response r = http.get(baseUrl + path, Map.of());
            if (r == null || !r.ok()) throw new ApiException("CoinGecko 实时价 -> " + (r == null ? -1 : r.status()), r == null ? -1 : r.status());
            body = r.body();
            cache.put(key, body);
        }
        try {
            JsonNode j = om.readTree(body);
            j.fields().forEachRemaining(e -> {
                JsonNode usd = e.getValue().get("usd");
                if (usd != null && usd.isNumber()) {
                    long t = e.getValue().path("last_updated_at").asLong(0);
                    out.put(e.getKey(), new LivePrice(usd.asDouble(), t > 0 ? java.time.Instant.ofEpochSecond(t) : null));
                }
            });
        } catch (IOException e) {
            throw new ApiException("实时价无法解析", 200);
        }
        return out;
    }

    /**
     * 原样请求，供 P0 归档保存接口原始返回值。同一请求 2 天内已被每日扫描取过时直接复用（保留原始请求时间），
     * 新取到的也写进缓存，扫描可复用。
     */
    public RateLimitedHttp.Response rawGet(String pathAndQuery) throws InterruptedException {
        refreshKey(false);
        String key = "cg:" + pathAndQuery;
        var hit = cache.getWithTime(key, Duration.ofDays(2));
        if (hit.isPresent()) {
            return new RateLimitedHttp.Response(200, hit.get().getKey(), baseUrl + pathAndQuery, hit.get().getValue());
        }
        RateLimitedHttp.Response r = send(pathAndQuery);
        if (r != null && r.ok()) cache.put(key, r.body());
        return r;
    }

    private JsonNode getJson(String pathAndQuery, Duration ttl) throws ApiException, InterruptedException {
        refreshKey(false);
        String key = "cg:" + pathAndQuery;
        var cached = cache.get(key, ttl);
        if (cached.isPresent()) {
            try {
                return om.readTree(cached.get());
            } catch (IOException ignored) {
                // 缓存损坏就重新请求
            }
        }
        var neg = cache.negative(key, TTL_NEGATIVE);
        if (neg.isPresent()) throw new ApiException("CoinGecko " + pathAndQuery + " -> " + neg.get() + "（缓存）", neg.get());
        RateLimitedHttp.Response r = send(pathAndQuery);
        if (r == null || !r.ok()) {
            int st = r == null ? -1 : r.status();
            if (st == 404) cache.putNegative(key, st);
            throw new ApiException("CoinGecko " + pathAndQuery + " -> " + st, st);
        }
        JsonNode node;
        try {
            node = om.readTree(r.body());
        } catch (IOException e) {
            throw new ApiException("CoinGecko 返回无法解析：" + pathAndQuery, 200);
        }
        cache.put(key, r.body());
        return node;
    }

    public List<MarketCoin> marketsPage(int page, String category) throws ApiException, InterruptedException {
        String q = "/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=250&page=" + page
                + (category == null ? "" : "&category=" + category);
        JsonNode arr = getJson(q, category == null ? TTL_MARKETS : TTL_CATEGORY);
        List<MarketCoin> out = new ArrayList<>();
        for (JsonNode c : arr) {
            out.add(new MarketCoin(c.path("id").asText(), c.path("symbol").asText(), c.path("name").asText(),
                    intOrNull(c.get("market_cap_rank")), num(c.get("current_price")), num(c.get("market_cap")),
                    num(c.get("fully_diluted_valuation")), num(c.get("circulating_supply")),
                    num(c.get("total_supply")), num(c.get("max_supply")), num(c.get("total_volume"))));
        }
        return out;
    }

    public Set<String> categoryIds(String category) throws ApiException, InterruptedException {
        Set<String> ids = new HashSet<>();
        for (int page = 1; page <= 8; page++) {
            List<MarketCoin> p = marketsPage(page, category);
            p.forEach(c -> ids.add(c.id()));
            if (p.size() < 250) break;
        }
        return ids;
    }

    public static final Duration TTL_TICKERS = Duration.ofDays(7);
    /** CoinGecko 交易所 id → 显示名（V2.6 质量分第 4 项的主流交易所）。gdax = Coinbase Exchange，okex = OKX。 */
    public static final Map<String, String> MAJOR_EXCHANGES = new java.util.LinkedHashMap<>();

    static {
        MAJOR_EXCHANGES.put("binance", "Binance");
        MAJOR_EXCHANGES.put("gdax", "Coinbase");
        MAJOR_EXCHANGES.put("okex", "OKX");
        MAJOR_EXCHANGES.put("bybit_spot", "Bybit");
        MAJOR_EXCHANGES.put("kraken", "Kraken");
        MAJOR_EXCHANGES.put("upbit", "Upbit");
    }

    /** 该币在主流交易所的现货交易对（/coins/{id}/tickers 只含现货；排除过期和异常报价）。7 天缓存，每币 1 次请求。 */
    public List<String> majorSpotExchanges(String id) throws ApiException, InterruptedException {
        JsonNode j = getJson("/coins/" + id + "/tickers?exchange_ids=" + String.join(",", MAJOR_EXCHANGES.keySet())
                + "&include_exchange_logo=false&depth=false", TTL_TICKERS);
        return parseMajorExchanges(j);
    }

    static List<String> parseMajorExchanges(JsonNode j) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (JsonNode t : j.path("tickers")) {
            if (t.path("is_stale").asBoolean(false) || t.path("is_anomaly").asBoolean(false)) continue;
            String ex = t.path("market").path("identifier").asText("");
            if (MAJOR_EXCHANGES.containsKey(ex)) out.add(MAJOR_EXCHANGES.get(ex));
        }
        return new ArrayList<>(out);
    }

    /** 只返回精确落在 UTC 00:00 的点；最后一个非整点（当前价）丢弃。 */
    public TreeMap<LocalDate, DailyPoint> marketChart(String id, int days) throws ApiException, InterruptedException {
        JsonNode j = getJson("/coins/" + id + "/market_chart?vs_currency=usd&days=" + days + "&interval=daily", TTL_CHART);
        return parseChart(j);
    }

    static TreeMap<LocalDate, DailyPoint> parseChart(JsonNode j) {
        Map<Long, Double> caps = series(j.get("market_caps"));
        Map<Long, Double> vols = series(j.get("total_volumes"));
        TreeMap<LocalDate, DailyPoint> out = new TreeMap<>();
        JsonNode prices = j.get("prices");
        if (prices == null) return out;
        for (JsonNode p : prices) {
            long ts = p.get(0).asLong();
            if (ts % 86_400_000L != 0) continue;
            LocalDate d = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC).toLocalDate();
            out.put(d, new DailyPoint(d, num(p.get(1)), caps.get(ts), vols.get(ts)));
        }
        return out;
    }

    private static Map<Long, Double> series(JsonNode arr) {
        Map<Long, Double> m = new HashMap<>();
        if (arr != null) for (JsonNode p : arr) m.put(p.get(0).asLong(), num(p.get(1)));
        return m;
    }

    static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }

    static Integer intOrNull(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asInt();
    }
}
