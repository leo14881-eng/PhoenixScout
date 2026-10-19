package com.phoenixscout.llama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.http.ApiCache;
import com.phoenixscout.http.RateLimitedHttp;
import com.phoenixscout.market.ApiException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DefiLlama 免费接口。批量优先：协议收入一次拿全部（/overview/fees），代币映射一次拿全部（/protocols），
 * 只有入围的少数协议才取逐日收入（/summary/fees/{slug}），且都有缓存。
 * 注意：DefiLlama 只覆盖 DeFi 协议，路径 B 天然偏向 DeFi（报告必须写明）。
 */
public class DefiLlamaClient {

    public static final Duration TTL_OVERVIEW = Duration.ofHours(12);
    public static final Duration TTL_PROTOCOLS = Duration.ofDays(7);
    public static final Duration TTL_SUMMARY = Duration.ofDays(3);

    /** 协议收入概览：total30d 等为协议收入（dailyRevenue 口径），不是持币人利润。 */
    public record ProtocolRevenue(String slug, String name, String category, String parentProtocol,
                                  Double total30d, Double total60dto30d, Double total1y, String geckoId) { }

    private final String baseUrl;
    private final RateLimitedHttp http;
    private final ApiCache cache;
    private final ObjectMapper om = new ObjectMapper();

    public DefiLlamaClient(String baseUrl, RateLimitedHttp http, ApiCache cache) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.cache = cache == null ? ApiCache.NONE : cache;
    }

    public RateLimitedHttp http() {
        return http;
    }

    private JsonNode get(String path, Duration ttl) throws ApiException, InterruptedException {
        String key = "llama:" + path;
        var c = cache.get(key, ttl);
        if (c.isPresent()) {
            try {
                return om.readTree(c.get());
            } catch (IOException ignored) {
                // 缓存损坏就重新请求
            }
        }
        // 确定不存在的地址（400/404，例如没有合并收入接口的母协议）记 3 天，不再重复请求
        var neg = cache.negative(key, TTL_SUMMARY);
        if (neg.isPresent()) throw new ApiException("DefiLlama " + path + " -> " + neg.get() + "（缓存）", neg.get());
        RateLimitedHttp.Response r = http.get(baseUrl + path, Map.of());
        if (r == null || !r.ok()) {
            int st = r == null ? -1 : r.status();
            if (st == 400 || st == 404) cache.putNegative(key, st);
            throw new ApiException("DefiLlama " + path + " -> " + st, st);
        }
        try {
            JsonNode n = om.readTree(r.body());
            cache.put(key, r.body());
            return n;
        } catch (IOException e) {
            throw new ApiException("DefiLlama 返回无法解析：" + path, 200);
        }
    }

    /** 两次批量请求：全部协议收入 + 全部协议的 CoinGecko id 映射。 */
    public List<ProtocolRevenue> revenueOverview() throws ApiException, InterruptedException {
        Map<String, String> geckoBySlug = new HashMap<>();
        Map<String, String> geckoByParent = new HashMap<>();
        geckoMaps(geckoBySlug, geckoByParent);
        JsonNode ov = get("/overview/fees?excludeTotalDataChart=true&excludeTotalDataChartBreakdown=true"
                + "&dataType=dailyRevenue", TTL_OVERVIEW);
        List<ProtocolRevenue> out = new ArrayList<>();
        for (JsonNode p : ov.path("protocols")) {
            String slug = text(p.get("slug"));
            String parent = text(p.get("parentProtocol"));
            String gid = slug == null ? null : geckoBySlug.get(slug);
            if (gid == null && parent != null) gid = geckoByParent.get(parent);
            out.add(new ProtocolRevenue(slug, text(p.get("name")), text(p.get("category")), parent,
                    num(p.get("total30d")), num(p.get("total60dto30d")), num(p.get("total1y")), gid));
        }
        return out;
    }

    /**
     * 持币人收入（dailyHoldersRevenue：回购、销毁、分给质押者的部分），按代币汇总最近 30 天。一次批量请求，12 小时缓存。
     * 用于质量分“价值捕获”：&gt; 0 才算有可验证的价值捕获。
     */
    public Map<String, Double> holdersRevenue30dByCoin() throws ApiException, InterruptedException {
        Map<String, String> geckoBySlug = new HashMap<>(), geckoByParent = new HashMap<>();
        geckoMaps(geckoBySlug, geckoByParent);
        JsonNode ov = get("/overview/fees?excludeTotalDataChart=true&excludeTotalDataChartBreakdown=true"
                + "&dataType=dailyHoldersRevenue", TTL_OVERVIEW);
        Map<String, Double> out = new HashMap<>();
        for (JsonNode p : ov.path("protocols")) {
            String slug = text(p.get("slug"));
            String parent = text(p.get("parentProtocol"));
            String gid = slug == null ? null : geckoBySlug.get(slug);
            if (gid == null && parent != null) gid = geckoByParent.get(parent);
            Double v = num(p.get("total30d"));
            if (gid != null && v != null) out.merge(gid, v, Double::sum);
        }
        return out;
    }

    /** 按 slug 汇总近 30 天 / 前 30 天的某类收入（dataType = dailyRevenue / dailyHoldersRevenue），重点观察用。复用 12 小时缓存。 */
    public Map<String, double[]> bySlug(String dataType) throws ApiException, InterruptedException {
        JsonNode ov = get("/overview/fees?excludeTotalDataChart=true&excludeTotalDataChartBreakdown=true"
                + "&dataType=" + dataType, TTL_OVERVIEW);
        Map<String, double[]> out = new HashMap<>();
        for (JsonNode p : ov.path("protocols")) {
            String slug = text(p.get("slug"));
            if (slug == null) continue;
            Double a = num(p.get("total30d")), b = num(p.get("total60dto30d"));
            out.put(slug, new double[]{a == null ? Double.NaN : a, b == null ? Double.NaN : b});
        }
        return out;
    }

    /** 有第三方审计记录的代币（/protocols 的 audits 数量 &gt; 0 或有 audit_links），复用 7 天缓存，不额外请求。 */
    public java.util.Set<String> auditedCoins() throws ApiException, InterruptedException {
        java.util.Set<String> out = new java.util.HashSet<>();
        Map<String, String> parents = parentGeckoIds();
        for (JsonNode p : get("/protocols", TTL_PROTOCOLS)) {
            String gid = text(p.get("gecko_id"));
            String parent = text(p.get("parentProtocol"));
            if (gid == null && parent != null) gid = parents.get(parent);   // 子协议的代币记在母协议上（如 Fluid Lending → instadapp）
            if (gid == null) continue;
            JsonNode a = p.get("audits");
            boolean audited = a != null && !a.isNull() && a.asText("0").matches("[1-9]\\d*");
            JsonNode links = p.get("audit_links");
            if (links != null && links.isArray() && !links.isEmpty()) audited = true;
            if (audited) out.add(gid);
        }
        return out;
    }

    /**
     * 协议 slug → CoinGecko id，以及母协议 id → CoinGecko id。
     * 母协议自己的 gecko_id（/lite/protocols2 的 parentProtocols）优先：很多子协议（如 Fluid Lending、Fluid DEX）
     * 在 /protocols 里没有 gecko_id，代币只记在母协议上；以前漏读母协议，导致这类项目的收入、审计对不上代币（2026-10-18 发现）。
     */
    private void geckoMaps(Map<String, String> geckoBySlug, Map<String, String> geckoByParent) throws ApiException, InterruptedException {
        for (JsonNode p : get("/protocols", TTL_PROTOCOLS)) {
            String gid = text(p.get("gecko_id"));
            if (gid == null) continue;
            String slug = text(p.get("slug"));
            if (slug != null) geckoBySlug.put(slug, gid);
            String parent = text(p.get("parentProtocol"));
            if (parent != null) geckoByParent.putIfAbsent(parent, gid);
        }
        geckoByParent.putAll(parentGeckoIds());
    }

    /** 母协议 id（如 parent#fluid）→ CoinGecko id。一次请求，7 天缓存；获取失败返回空表（退回只用子协议的映射）。 */
    public Map<String, String> parentGeckoIds() throws InterruptedException {
        try {
            return parseParentGeckoIds(get("/lite/protocols2", TTL_PROTOCOLS));
        } catch (ApiException e) {
            return Map.of();
        }
    }

    static Map<String, String> parseParentGeckoIds(JsonNode j) {
        Map<String, String> out = new HashMap<>();
        for (JsonNode p : j.path("parentProtocols")) {
            String id = text(p.get("id")), gid = text(p.get("gecko_id"));
            if (id != null && gid != null) out.put(id, gid);
        }
        return out;
    }

    /** 单个协议逐日收入（UTC 日期 → 当日收入）。 */
    public TreeMap<LocalDate, Double> dailyRevenue(String slug) throws ApiException, InterruptedException {
        JsonNode j = get("/summary/fees/" + slug + "?dataType=dailyRevenue", TTL_SUMMARY);
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        for (JsonNode p : j.path("totalDataChart")) {
            if (p.size() < 2 || !p.get(1).isNumber()) continue;
            out.put(Instant.ofEpochSecond(p.get(0).asLong()).atZone(ZoneOffset.UTC).toLocalDate(), p.get(1).asDouble());
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    private static Double num(JsonNode n) {
        return n == null || n.isNull() || !n.isNumber() ? null : n.asDouble();
    }
}
