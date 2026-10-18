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
        RateLimitedHttp.Response r = http.get(baseUrl + path, Map.of());
        if (r == null || !r.ok()) throw new ApiException("DefiLlama " + path + " -> " + (r == null ? -1 : r.status()),
                r == null ? -1 : r.status());
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
        JsonNode protocols = get("/protocols", TTL_PROTOCOLS);
        Map<String, String> geckoBySlug = new HashMap<>();
        Map<String, String> geckoByParent = new HashMap<>();
        for (JsonNode p : protocols) {
            String gid = text(p.get("gecko_id"));
            if (gid == null) continue;
            String slug = text(p.get("slug"));
            if (slug != null) geckoBySlug.put(slug, gid);
            String parent = text(p.get("parentProtocol"));
            if (parent != null) geckoByParent.putIfAbsent(parent, gid);
        }
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

    /** 有第三方审计记录的代币（/protocols 的 audits 数量 &gt; 0 或有 audit_links），复用 7 天缓存，不额外请求。 */
    public java.util.Set<String> auditedCoins() throws ApiException, InterruptedException {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonNode p : get("/protocols", TTL_PROTOCOLS)) {
            String gid = text(p.get("gecko_id"));
            if (gid == null) continue;
            JsonNode a = p.get("audits");
            boolean audited = a != null && !a.isNull() && a.asText("0").matches("[1-9]\\d*");
            JsonNode links = p.get("audit_links");
            if (links != null && links.isArray() && !links.isEmpty()) audited = true;
            if (audited) out.add(gid);
        }
        return out;
    }

    private void geckoMaps(Map<String, String> geckoBySlug, Map<String, String> geckoByParent) throws ApiException, InterruptedException {
        for (JsonNode p : get("/protocols", TTL_PROTOCOLS)) {
            String gid = text(p.get("gecko_id"));
            if (gid == null) continue;
            String slug = text(p.get("slug"));
            if (slug != null) geckoBySlug.put(slug, gid);
            String parent = text(p.get("parentProtocol"));
            if (parent != null) geckoByParent.putIfAbsent(parent, gid);
        }
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
