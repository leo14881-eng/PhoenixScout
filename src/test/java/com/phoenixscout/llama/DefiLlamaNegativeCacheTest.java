package com.phoenixscout.llama;

import com.phoenixscout.http.RateLimitedHttp;
import com.phoenixscout.market.ApiException;
import com.phoenixscout.store.SqliteApiCache;
import com.phoenixscout.support.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 没有合并收入接口的母协议（如 compound 返回 400）只请求一次，之后 3 天直接走子协议，不再计入失败。 */
class DefiLlamaNegativeCacheTest {

    @Test
    void badRequestIsRememberedAndNotRequestedAgain(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        AtomicInteger calls = new AtomicInteger();
        var http = new RateLimitedHttp("defillama", 0, 1, (u, h) -> {
            calls.incrementAndGet();
            return new RateLimitedHttp.Response(400, "{}", u, Instant.now());
        }, ms -> { });
        var c = new DefiLlamaClient("http://ll", http, new SqliteApiCache(db.jdbc));
        var e1 = assertThrows(ApiException.class, () -> c.dailyRevenue("compound"));
        assertEquals(400, e1.status());
        var e2 = assertThrows(ApiException.class, () -> c.dailyRevenue("compound"));
        assertTrue(e2.getMessage().contains("（缓存）"));
        assertEquals(1, calls.get(), "第二次不再请求");
        assertEquals(1, http.failedCount());
    }
}
