package com.phoenixscout.research;

import com.phoenixscout.config.PsProperties;
import com.phoenixscout.notify.TelegramNotifier;
import com.phoenixscout.support.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResearchServiceTest {

    static String item(boolean ok, String extra) {
        return "{\"ok\":" + ok + ",\"claim\":\"已核实\"" + extra
                + ",\"sources\":[{\"url\":\"https://example.org/a\",\"date\":\"2026-10-10\"}]}";
    }

    static String research(boolean liquidityOk) {
        return "{\"coin_id\":\"hero\",\"researched_at\":\"2026-10-17T03:00:00Z\",\"items\":{"
                + "\"identity\":" + item(true, "") + ","
                + "\"supply_verifiable\":" + item(true, "") + ","
                + "\"economic_activity\":" + item(true, "") + ","
                + "\"value_capture\":" + item(true, ",\"mechanism\":\"buyback\"") + ","
                + "\"liquidity\":" + item(liquidityOk, ",\"spot_volume_30d_usd\":5e7") + ","
                + "\"no_security_issue\":" + item(true, "") + ","
                + "\"data_consistent\":" + item(true, "") + ","
                + "\"supply_growth\":" + item(true, ",\"supply_growth_12m_pct\":10") + "},"
                + "\"scenario_inputs\":{\"price_usd\":1.0,\"circulating_supply\":1e8,\"total_supply\":2e8,"
                + "\"supply_by_month\":{\"12\":1.1e8,\"36\":1.2e8},\"annual_revenue_usd\":1e7,"
                + "\"revenue_growth\":{\"conservative\":0.0,\"base\":0.5,\"optimistic\":1.0},"
                + "\"peer_multiples\":{\"p25\":5,\"median\":10,\"p75\":20}},"
                + "\"risks\":[\"代币解锁\"],\"unverified\":[\"持币集中度\"],\"summary_reason\":\"收入增长且已执行回购\"}";
    }

    static class Capture extends TelegramNotifier {
        final List<String> sent = new ArrayList<>();

        Capture() {
            super(new PsProperties.Telegram("x", "y"));
        }

        @Override
        public boolean send(String text) {
            sent.add(text);
            return true;
        }
    }

    @Test
    void gradesAThenDowngradeAndRejectsBadFile(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        db.jdbc.update("INSERT INTO market_snapshot(scan_day, coin_id, symbol, name, price, market_cap, fdv, requested_at)"
                + " VALUES ('2026-10-17','hero','hero','Hero',1.0,1e8,2e8,'t')");
        Path rd = Files.createDirectories(dir.resolve("research"));
        var tg = new Capture();
        var svc = new ResearchService(rd, db.jdbc, tg, Clock.systemUTC());

        Files.writeString(rd.resolve("research-2026-10-17-hero.json"), research(true));
        assertEquals(1, svc.ingest());
        assertEquals("A", svc.previousGrade("hero"));
        assertEquals(1, tg.sent.size());
        String msg = tg.sent.get(0);
        assertTrue(msg.contains("新 A 级：Hero（HERO）"), msg);
        assertTrue(msg.contains("https://example.org/a"));
        assertTrue(msg.contains("情景推算，不是预测或承诺"));
        assertTrue(msg.contains("乐观"));
        assertEquals(0, svc.ingest(), "同一文件只处理一次");

        Files.writeString(rd.resolve("research-2026-10-18-hero.json"), research(false));
        svc.ingest();
        assertEquals("B", svc.previousGrade("hero"));
        assertTrue(tg.sent.get(1).contains("降级"));
        assertTrue(tg.sent.get(1).contains("现货流动性"));

        Files.writeString(rd.resolve("research-bad.json"), "{not json");
        svc.ingest();
        assertTrue(tg.sent.get(2).contains("格式不对"));
    }

    @Test
    void missingSourceDateFailsItem() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var r = om.readTree(research(true).replace("\"date\":\"2026-10-10\"", "\"date\":\"recent\""));
        var res = ChecklistEvaluator.evaluate(r, "A", 1.0);
        assertEquals("B", res.grade());
        assertTrue(res.failed().contains("identity"));
        assertFalse(res.failed().contains("valuation_5x"), "第 7 项由代码计算，不依赖来源");
    }

    @Test
    void pathCRequiresAudit() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var res = ChecklistEvaluator.evaluate(om.readTree(research(true)), "C", 1.0);
        assertTrue(res.failed().contains("audit"));
    }

    @Test
    void lowCirculatingRatioFailsWithoutEvidence() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var res = ChecklistEvaluator.evaluate(om.readTree(research(true).replace("\"total_supply\":2e8", "\"total_supply\":5e8")), "A", 1.0);
        assertTrue(res.failed().contains("supply_growth"), "流通量只占 20%");
    }

    @Test
    void highSupplyGrowthWithoutEvidenceFails() throws Exception {
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        var res = ChecklistEvaluator.evaluate(om.readTree(research(true).replace("\"supply_growth_12m_pct\":10", "\"supply_growth_12m_pct\":30")), "A", 1.0);
        assertTrue(res.failed().contains("supply_growth"));
    }
}
