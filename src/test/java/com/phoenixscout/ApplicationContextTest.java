package com.phoenixscout;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "phoenixscout.data-dir=target/ctx-data",
        "phoenixscout.scan.catch-up-on-start=false",
        "phoenixscout.scan.cron=0 0 0 1 1 *",
        "logging.file.name=target/ctx-data/test.log"})
class ApplicationContextTest {

    @BeforeAll
    static void dirs() throws Exception {
        Files.createDirectories(Path.of("target/ctx-data"));
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("unchecked")
    void healthAndAdminGuard() {
        Map<String, Object> h = rest.getForObject("/api/health", Map.class);
        assertEquals(Boolean.TRUE, h.get("ok"));
        assertEquals(Boolean.FALSE, h.get("scanRunning"));
        var r = rest.postForEntity("/api/admin/shutdown", null, Map.class);
        assertEquals(403, r.getStatusCode().value(), "没有管理头不能停机");
        String page = rest.getForObject("/", String.class);
        assertTrue(page.contains("PhoenixScout 状态"), page);
        assertTrue(page.contains("模拟仓"));
        Map<String, Object> sim = rest.getForObject("/api/sim", Map.class);
        assertNotNull(sim.get("totals"));
        assertEquals(10000.0, ((Map<String, Object>) sim.get("totals")).get("startEquity"));
    }
}
