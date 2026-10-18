package com.phoenixscout.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.research.QualityScore;
import com.phoenixscout.research.UnlockRisk;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** V2.6：估值目标、质量分、解锁标签、期望值。 */
class V26RulesTest {

    static final ObjectMapper OM = new ObjectMapper();

    static List<Valuation.Peer> peers(String cat, int n, double mult) {
        List<Valuation.Peer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Valuation.Peer(cat, mult * 10e6, 10e6));
        return out;
    }

    @Test
    void valuationTargetUsesRevenueAndConservativeSupply() {
        var ps = Valuation.peers("Dexs", peers("Dexs", 9, 20));
        assertEquals(20, ps.median(), 1e-9);
        // 年化收入 1,000 万 × 20 倍 = 2 亿；没有解锁数据 → 按总量 4 亿枚 → 目标价 0.5
        var t = Valuation.target(0.25, 10e6, "Dexs", ps, 2e8, null, 4e8, null);
        assertTrue(t.available());
        assertEquals(0.5, t.targetPrice(), 1e-9);
        assertEquals(1.0, t.upside(), 1e-9);
        assertTrue(t.supplyBasis().contains("总量"));
        // 研究核实了 12 个月流通量 2.5 亿 → 目标价 0.8
        assertEquals(0.8, Valuation.target(0.25, 10e6, "Dexs", ps, 2e8, 2.5e8, 4e8, null).targetPrice(), 1e-9);
        // 没有收入 → 无法估值（不再用历史价格当目标）
        var none = Valuation.target(0.25, null, "Dexs", ps, 2e8, null, 4e8, null);
        assertFalse(none.available());
        assertTrue(none.reason().contains("无法估值"));
        assertFalse(Valuation.target(0.25, 10e6, "Chain", ps, 2e8, null, 4e8, null).available(), "公链不按收入估值");
        assertNull(Valuation.peers("Dexs", peers("Dexs", 5, 20)), "同类不足 8 个且全部协议也不足 → 无法估值");
    }

    @Test
    void qualityScoreRules() throws Exception {
        var none = QualityScore.evaluate(new QualityScore.Auto(false, false, List.of("Binance"), false), null);
        assertEquals(1, none.score());
        assertFalse(none.researched());
        assertFalse(none.excluded(), "没研究过的不排除，标待研究");
        assertEquals("B", none.cap());
        assertTrue(none.label().contains("待研究"));

        var researchedLow = QualityScore.evaluate(new QualityScore.Auto(false, false, List.of(), false), OM.readTree("{\"items\":{}}"));
        assertTrue(researchedLow.excluded(), "研究过且 ≤1 分排除");

        String src = "\"sources\":[{\"url\":\"https://example.com/a\",\"date\":\"2026-10-01\"}]";
        var full = OM.readTree("{\"items\":{\"team\":{\"ok\":true," + src + "},\"value_capture\":{\"ok\":true,\"mechanism\":\"buyback\"," + src
                + "},\"audit\":{\"ok\":true," + src + "}}}");
        var good = QualityScore.evaluate(new QualityScore.Auto(true, false, List.of("OKX"), false), full);
        assertEquals(5, good.score());
        assertEquals("A", good.cap());

        var self = OM.readTree("{\"self_reported_mcap\":true,\"items\":{\"team\":{\"ok\":true," + src + "},\"audit\":{\"ok\":true," + src + "}}}");
        var sr = QualityScore.evaluate(new QualityScore.Auto(true, true, List.of("OKX"), false), self);
        assertEquals(4, sr.score(), "5 项满足，自报市值扣 1 分");
        // 团队项没有来源 → 不得分
        var noSrc = OM.readTree("{\"items\":{\"team\":{\"ok\":true}}}");
        assertFalse(QualityScore.evaluate(new QualityScore.Auto(false, false, List.of(), false), noSrc).items().get("团队背景"));
    }

    @Test
    void unlockFlags() throws Exception {
        var missing = UnlockRisk.evaluate(2e8, 1e9, null);
        assertEquals(0.2, missing.circRatio(), 1e-9);
        assertTrue(missing.flags().stream().anyMatch(f -> f.contains("高稀释")));
        assertTrue(missing.flags().contains("解锁数据缺失"));
        assertTrue(missing.blocksA());

        String src = "\"sources\":[{\"url\":\"https://example.com/u\",\"date\":\"2026-10-01\"}]";
        var heavy = UnlockRisk.evaluate(6e8, 1e9, OM.readTree("{\"items\":{\"unlock\":{\"ok\":true,\"unlock_12m_pct\":20,"
                + "\"unlock_36m_pct\":150,\"next_unlock_date\":\"2026-11-01\",\"next_unlock_amount\":5e7," + src + "}}}"));
        assertFalse(heavy.blocksA());
        assertTrue(heavy.flags().stream().anyMatch(f -> f.contains("牛市窗口抛压")));
        assertEquals(7.2e8, heavy.supply12m(), 1);

        var conflict = UnlockRisk.evaluate(6.9e8, 1e9, OM.readTree("{\"items\":{\"unlock\":{\"ok\":true,\"unlock_12m_pct\":0,"
                + "\"fully_unlocked\":true," + src + "}}}"));
        assertTrue(conflict.flags().contains("解锁数据冲突"), "写全部解锁但流通率 69%");
        assertTrue(conflict.blocksA());
    }

    @Test
    void expectedValueFromHistoricalSamples() {
        LocalDate d0 = LocalDate.of(2026, 1, 1);
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = new java.util.HashMap<>();
        List<StrategyBacktest.Signal> eps = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            TreeMap<LocalDate, DailyPoint> h = new TreeMap<>();
            // 前 10 个涨到 +100%，接着 20 个跌到 −40%，最后 10 个横盘
            for (int k = 0; k <= 100; k++) {
                double p = i < 10 ? 1 + k * 0.02 : i < 30 ? 1 - Math.min(0.5, k * 0.01) : 1.0;
                h.put(d0.plusDays(k), new DailyPoint(d0.plusDays(k), p, 1e8, 5e6));
            }
            hist.put("c" + i, h);
            eps.add(new StrategyBacktest.Signal("c" + i, d0, 1.0, 0));
        }
        var ev = StrategyBacktest.expectedValue(eps, hist, d0.plusDays(100), 1.0, 0.35);
        assertTrue(ev.available());
        assertEquals(40, ev.n());
        assertEquals(0.25, ev.pTarget(), 1e-9);
        assertEquals(0.5, ev.pStop(), 1e-9);
        assertEquals(0.25 * 1.0 - 0.5 * 0.35, ev.ev(), 1e-9);
        assertFalse(StrategyBacktest.expectedValue(eps.subList(0, 20), hist, d0.plusDays(100), 1.0, 0.35).available(), "样本 <30 不输出");
        assertFalse(StrategyBacktest.expectedValue(eps, hist, d0.plusDays(100), null, 0.35).available(), "无法估值没有期望值");
        assertFalse(StrategyBacktest.expectedValue(eps, hist, d0.plusDays(50), 1.0, 0.35).available(), "未满 90 天的样本不计入");
    }
}
