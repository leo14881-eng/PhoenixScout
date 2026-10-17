package com.phoenixscout.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每个数据源一个实例、一个限流器：两次请求之间至少间隔 minIntervalMs；
 * 429 / 5xx / 网络错误按 Retry-After 或递增退避重试，404 等直接返回不重试。
 * 失败只返回状态，绝不编造数据。
 */
public class RateLimitedHttp {

    public record Response(int status, String body, String url, Instant requestedAt) {
        public boolean ok() {
            return status == 200;
        }
    }

    /** 可替换的休眠，测试里不真睡。 */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public interface Transport {
        Response send(String url, Map<String, String> headers) throws IOException, InterruptedException;
    }

    private final String name;
    private volatile long minIntervalMs;
    private final int maxAttempts;
    private final Transport transport;
    private final Sleeper sleeper;
    private long lastRequestAt;
    private final AtomicInteger ok = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger rateLimited = new AtomicInteger();

    public RateLimitedHttp(String name, long minIntervalMs) {
        this(name, minIntervalMs, 7, defaultTransport(), Thread::sleep);
    }

    public RateLimitedHttp(String name, long minIntervalMs, int maxAttempts, Transport transport, Sleeper sleeper) {
        this.name = name;
        this.minIntervalMs = minIntervalMs;
        this.maxAttempts = maxAttempts;
        this.transport = transport;
        this.sleeper = sleeper;
    }

    public String name() {
        return name;
    }

    public void setMinIntervalMs(long ms) {
        this.minIntervalMs = ms;
    }

    public long minIntervalMs() {
        return minIntervalMs;
    }

    public synchronized Response get(String url, Map<String, String> headers) throws InterruptedException {
        Response last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long wait = minIntervalMs - (System.currentTimeMillis() - lastRequestAt);
            if (wait > 0) sleeper.sleep(wait);
            lastRequestAt = System.currentTimeMillis();
            try {
                last = transport.send(url, headers);
            } catch (IOException e) {
                last = new Response(-1, e.getClass().getSimpleName(), url, Instant.now());
                sleeper.sleep(Math.min(300_000L, 15_000L * attempt));
                continue;
            }
            int s = last.status();
            if (s == 200) {
                ok.incrementAndGet();
                return last;
            }
            if (s == 429 || s >= 500) {
                if (s == 429) rateLimited.incrementAndGet();
                sleeper.sleep(backoffMs(last, attempt));
                continue;
            }
            failed.incrementAndGet();
            return last;
        }
        failed.incrementAndGet();
        return last;
    }

    private long backoffMs(Response r, int attempt) {
        String body = r.body();
        if (body != null && body.startsWith("retry-after:")) {
            try {
                return Math.min(600_000L, Long.parseLong(body.substring(12).trim()) * 1000L);
            } catch (NumberFormatException ignored) {
                // 落到默认退避
            }
        }
        return Math.min(600_000L, 65_000L * attempt);
    }

    public int okCount() {
        return ok.get();
    }

    public int failedCount() {
        return failed.get();
    }

    public int rateLimitedCount() {
        return rateLimited.get();
    }

    public void resetCounters() {
        ok.set(0);
        failed.set(0);
        rateLimited.set(0);
    }

    static Transport defaultTransport() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return (url, headers) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "PhoenixScout/0.1").header("Accept", "application/json");
            headers.forEach(b::header);
            Instant at = Instant.now();
            HttpResponse<String> resp = client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            if (resp.statusCode() == 429) {
                // 把 Retry-After 带出来给退避逻辑用
                body = resp.headers().firstValue("Retry-After").map(v -> "retry-after:" + v).orElse(body);
            }
            return new Response(resp.statusCode(), body, url, at);
        };
    }
}
