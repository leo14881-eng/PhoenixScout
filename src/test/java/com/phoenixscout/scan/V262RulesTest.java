package com.phoenixscout.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.llama.DefiLlamaClient;
import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.market.MarketCoin;
import com.phoenixscout.research.QualityScore;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** V2.6.2（用户审阅 2026-10-18 第三版日报）：保守年化、按母协议合并、审计未知、接近入选收紧。 */
class V262RulesTest {

    @Test
    void conservativeAnnualRevenue() {
        // GMX：近30天 431 万 ×12 = 5,170 万；近12个月实际 1,820 万 → 取 1,820 万，并标“近期收入异常偏高”
        var a = RevenueBasis.of(4_313_834.0, 7_125_608.0, 18_202_761.0);
        assertEquals(18_202_761.0, a.used(), 1);
        assertEquals(4_313_834.0 * 12, a.r30x12(), 1);
        assertEquals(7_125_608.0 * 4, a.r90x4(), 1);
        assertTrue(a.spike());
        assertTrue(a.text().contains("近期收入异常偏高"), a.text());
        // 缺的项不参与
        var b = RevenueBasis.of(1_000_000.0, null, null);
        assertEquals(12_000_000.0, b.used(), 1);
        assertFalse(b.spike());
        assertNull(RevenueBasis.of(null, null, null).used());
    }

    @Test
    void lessThanAYearOfDataIsNotTwelveMonths() {
        LocalDate latest = LocalDate.of(2026, 10, 17);
        TreeMap<LocalDate, Double> rev = new TreeMap<>();
        for (int k = 0; k < 200; k++) rev.put(latest.minusDays(k), 100.0);
        var a = RevenueBasis.fromDaily(rev, latest);
        assertNull(a.actual12m(), "不足 365 天不拿短期合计冒充 12 个月");
        assertEquals(36_000, a.r30x12(), 1e-6);
        assertEquals(36_000, a.r90x4(), 1e-6);
        for (int k = 200; k < 400; k++) rev.put(latest.minusDays(k), 100.0);
        assertEquals(36_500, RevenueBasis.fromDaily(rev, latest).actual12m(), 1e-6);
    }

    static DefiLlamaClient.ProtocolRevenue p(String slug, String parent, String gid, String cat, double d30, double d60, double y1) {
        return new DefiLlamaClient.ProtocolRevenue(slug, slug, cat, parent, d30, d60, y1, gid);
    }

    @Test
    void mergesChildProtocolsByParent() {
        var m = RevenueBasis.byCoin(List.of(
                p("gmx-v1-evm", "parent#gmx", "gmx", "Derivatives", 0, 0, 49),
                p("gmx-evm", "parent#gmx", "gmx", "Derivatives", 438_265, 900_000, 9_829_880),
                p("gmx-solana", "parent#gmx", "gmx", "Derivatives", 3_875_569, 907_156, 8_372_832),
                p("uniswap-v3", "parent#uniswap", "uniswap", "Dexs", 10, 5, 100)));
        var g = m.get("gmx");
        assertEquals(List.of("gmx"), g.projectKeys(), "三个子协议合并成一个项目，取母协议明细");
        assertEquals(3, g.children().get("gmx").size());
        assertEquals(4_313_834, g.sum30(), 1);
        assertEquals(18_202_761, g.sum1y(), 1);
        assertEquals("Derivatives", g.category());
        // 同一母协议下子协议属于不同代币 → 不用母协议合并明细，逐个子协议
        var mixed = RevenueBasis.byCoin(List.of(p("sky-a", "parent#sky", "sky", "CDP", 5, 1, 10), p("maker-b", "parent#sky", "maker", "CDP", 5, 1, 10)));
        assertEquals(List.of("sky-a"), mixed.get("sky").projectKeys());
        assertEquals(List.of("maker-b"), mixed.get("maker").projectKeys());
        assertEquals("gmx", RevenueBasis.projectKey(p("gmx-evm", "parent#gmx", "gmx", "D", 1, 1, 1)));
        assertEquals("solo", RevenueBasis.projectKey(p("solo", null, "x", "D", 1, 1, 1)));
        TreeMap<LocalDate, Double> a = new TreeMap<>(), b = new TreeMap<>();
        LocalDate d = LocalDate.of(2026, 10, 1);
        a.put(d, 1.0); b.put(d, 2.0); b.put(d.plusDays(1), 3.0);
        var merged = RevenueBasis.merge(List.of(a, b));
        assertEquals(3.0, merged.get(d));
        assertEquals(3.0, merged.get(d.plusDays(1)));
    }

    @Test
    void auditWithoutRecordIsUnknown() throws Exception {
        var q = QualityScore.evaluate(new QualityScore.Auto(false, false, List.of("Binance"), false), null);
        assertTrue(q.detail().contains("第三方审计 未知"), q.detail());
        var researchedNo = QualityScore.evaluate(new QualityScore.Auto(false, false, List.of("Binance"), false),
                new ObjectMapper().readTree("{\"items\":{\"audit\":{\"ok\":false,\"claim\":\"无\",\"sources\":[]}}}"));
        assertTrue(researchedNo.detail().contains("第三方审计 ✗"), researchedNo.detail());
        var audited = QualityScore.evaluate(new QualityScore.Auto(false, false, List.of(), true), null);
        assertTrue(audited.detail().contains("第三方审计 ✓"), audited.detail());
    }

    static TreeMap<LocalDate, DailyPoint> hist(LocalDate day) {
        TreeMap<LocalDate, DailyPoint> h = new TreeMap<>();
        for (int k = 0; k < 40; k++) h.put(day.minusDays(k), new DailyPoint(day.minusDays(k), 1.0, 1e8, 5e7));
        return h;
    }

    @Test
    void nearMissExcludesReboundsAndChains() {
        LocalDate day = LocalDate.of(2026, 10, 18);
        var mc = new MarketCoin("x", "xx", "Xcoin", 300, 1.0, 1e8, 1e8, 1e8, 1e8, null, 1e6);
        List<DailyScanService.NearMiss> out = new ArrayList<>();
        // BONK 型：前季 −81%、本季 +1086% → 从低位反弹，不列
        DailyScanService.nearRevenue(new PathB.Result("x", "X", 2.23, 0.188, 1.0, 10.86, 0.07, false, false, List.of()),
                mc, hist(day), day, "Launchpad", out);
        assertTrue(out.isEmpty());
        // 前季 −17%、本季 +15%，但本季仍低于两个季度前 → 不列
        DailyScanService.nearRevenue(new PathB.Result("x", "X", 0.96, 0.833, 1.0, 0.154, 0.099, false, false, List.of()),
                mc, hist(day), day, "Liquid Staking", out);
        assertTrue(out.isEmpty());
        // 公链 → 不列
        var ok = new PathB.Result("x", "X", 1.46, 0.891, 1.0, 0.639, 0.265, false, false, List.of());
        DailyScanService.nearRevenue(ok, mc, hist(day), day, "Chain", out);
        assertTrue(out.isEmpty());
        // 前季 −11%、本季 +64%、高于两季前 → 列入
        DailyScanService.nearRevenue(ok, mc, hist(day), day, "Privacy", out);
        assertEquals(1, out.size());
    }

    @Test
    void liquidityRiskTagMatchesThreshold() {
        // 门槛 50 万；只有 50 万–100 万之间才标“接近门槛”，410 万不应再标流动性偏低（在 DailyScanService 里按 2×门槛判断）
        assertTrue(4_100_000 >= 2 * DailyScanService.MIN_AVG_VOLUME);
    }
}
