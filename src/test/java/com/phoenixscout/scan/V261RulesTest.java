package com.phoenixscout.scan;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.market.MarketCoin;
import com.phoenixscout.research.QualityScore;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** V2.6.1（用户 2026-10-18 第二次审阅）：质量分逐项显示、流动性下限 50 万美元、接近入选。 */
class V261RulesTest {

    @Test
    void qualityDetailShowsEachItem() {
        var q = QualityScore.evaluate(new QualityScore.Auto(false, false, List.of("Binance", "Coinbase"), false), null);
        String d = q.detail();
        assertTrue(d.contains("团队背景 待研究"), d);
        assertTrue(d.contains("使用/收入数据 ✗"), d);
        assertTrue(d.contains("主流交易所现货 ✓（Binance、Coinbase）"), d);
        assertTrue(d.contains("第三方审计 未知"), d);   // V2.6.2：DefiLlama 没有记录显示“未知”
        var failed = QualityScore.evaluate(new QualityScore.Auto(true, false, null, true), null);
        assertTrue(failed.detail().contains("交易所数据获取失败"), failed.detail());
        assertTrue(failed.detail().contains("使用/收入数据 ✓"), failed.detail());
    }

    @Test
    void liquidityFloorIs500k() {
        assertEquals(500_000, DailyScanService.MIN_AVG_VOLUME);
        // 50 万日均时 2,500 美元仓位 = 0.5%，1% 规则同时满足
        assertTrue(DailyScanService.POSITION_USD <= DailyScanService.MIN_AVG_VOLUME * DailyScanService.MAX_POSITION_OF_VOLUME);
    }

    static TreeMap<LocalDate, DailyPoint> hist(LocalDate day, double vol) {
        TreeMap<LocalDate, DailyPoint> h = new TreeMap<>();
        for (int k = 0; k < 40; k++) h.put(day.minusDays(k), new DailyPoint(day.minusDays(k), 1.0, 1e8, vol));
        return h;
    }

    @Test
    void nearMissOnlyOneQuarterOfGrowth() {
        LocalDate day = LocalDate.of(2026, 10, 18);
        var mc = new MarketCoin("x", "xx", "Xcoin", 300, 1.0, 1e8, 1e8, 1e8, 1e8, null, 1e6);
        List<DailyScanService.NearMiss> out = new ArrayList<>();
        // 最近季度 +20%，前一季度 −10%（只差收入连增），币价 90 天 +5%，日均 100 万 → 列入
        var r = new PathB.Result("x", "X", 120.0, 100.0, 111.0, 0.20, 0.05, false, false, List.of());
        DailyScanService.nearRevenue(r, mc, hist(day, 1e6), day, "Dexs", out);
        assertEquals(1, out.size());
        assertTrue(out.get(0).missing().startsWith("只差收入连增"), out.get(0).missing());
        // 流动性不足 → 不列
        out.clear();
        DailyScanService.nearRevenue(r, mc, hist(day, 1e5), day, "Dexs", out);
        assertTrue(out.isEmpty());
        // 币价涨得比收入快 → 不列（差了两个条件）
        DailyScanService.nearRevenue(new PathB.Result("x", "X", 120.0, 100.0, 111.0, 0.20, 0.50, false, false, List.of()),
                mc, hist(day, 1e6), day, "Dexs", out);
        assertTrue(out.isEmpty());
        // 最近季度也没增长 → 不列
        DailyScanService.nearRevenue(new PathB.Result("x", "X", 90.0, 100.0, 111.0, -0.10, -0.30, false, false, List.of()),
                mc, hist(day, 1e6), day, "Dexs", out);
        assertTrue(out.isEmpty());
        // 没有价格历史 → 不列、不报错
        DailyScanService.nearRevenue(r, mc, null, day, "Dexs", out);
        assertTrue(out.isEmpty());
    }
}
