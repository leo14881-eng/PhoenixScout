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
    private final Map<String, String> headers = new HashMap<>();
    private final ObjectMapper om = new ObjectMapper();
    private final ApiCache cache;

    public CoinGeckoClient(String baseUrl, RateLimitedHttp http, Path demoKeyFile, ApiCache cache) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.cache = cache == null ? ApiCache.NONE : cache;
        if (demoKeyFile != null && Files.isRegularFile(demoKeyFile)) {
            try {
                String key = Files.readString(demoKeyFile, StandardCharsets.UTF_8).trim();
                if (!key.isEmpty()) {
                    headers.put("x-cg-demo-api-key", key);
                    http.setMinIntervalMs(Math.min(http.minIntervalMs(), 2200));
                }
            } catch (IOException ignored) {
                // 没有 key 就用公共接口
            }
        }
    }

    public boolean usingDemoKey() {
        return headers.containsKey("x-cg-demo-api-key");
    }

    public RateLimitedHttp http() {
        return http;
    }

    /** 原样请求（不走缓存），供 P0 归档保存接口原始返回值。 */
    public RateLimitedHttp.Response rawGet(String pathAndQuery) throws InterruptedException {
        return http.get(baseUrl + pathAndQuery, headers);
    }

    private JsonNode getJson(String pathAndQuery, Duration ttl) throws ApiException, InterruptedException {
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
        RateLimitedHttp.Response r = http.get(baseUrl + pathAndQuery, headers);
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
