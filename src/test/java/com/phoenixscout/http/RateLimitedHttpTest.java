package com.phoenixscout.http;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitedHttpTest {

    @Test
    void retriesOn429HonouringRetryAfter() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        int[] n = {0};
        var http = new RateLimitedHttp("t", 0, 5, (url, h) -> {
            n[0]++;
            return n[0] < 3 ? new RateLimitedHttp.Response(429, "retry-after:7", url, Instant.now())
                    : new RateLimitedHttp.Response(200, "{}", url, Instant.now());
        }, sleeps::add);
        var r = http.get("u", Map.of());
        assertTrue(r.ok());
        assertEquals(3, n[0]);
        assertTrue(sleeps.contains(7000L));
        assertEquals(2, http.rateLimitedCount());
        assertEquals(1, http.okCount());
    }

    @Test
    void notFoundIsNotRetried() throws Exception {
        int[] n = {0};
        var http = new RateLimitedHttp("t", 0, 5, (url, h) -> {
            n[0]++;
            return new RateLimitedHttp.Response(404, "", url, Instant.now());
        }, ms -> { });
        assertEquals(404, http.get("u", Map.of()).status());
        assertEquals(1, n[0]);
        assertEquals(1, http.failedCount());
    }

    @Test
    void givesUpAfterMaxAttempts() throws Exception {
        var http = new RateLimitedHttp("t", 0, 3, (url, h) -> new RateLimitedHttp.Response(503, "", url, Instant.now()), ms -> { });
        assertEquals(503, http.get("u", Map.of()).status());
        assertEquals(1, http.failedCount());
    }
}
