package com.phoenixscout.scan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrelimValuationTest {

    static List<PrelimValuation.Peer> peers() {
        List<PrelimValuation.Peer> p = new ArrayList<>();
        // 同类 Dexs：倍数 5,10,15,20,25；另有其他类别
        for (int m : new int[]{5, 10, 15, 20, 25}) p.add(new PrelimValuation.Peer("Dexs", m * 1e6, 1e6));
        for (int m : new int[]{50, 60}) p.add(new PrelimValuation.Peer("Lending", m * 1e6, 1e6));
        return p;
    }

    @Test
    void usesSameCategoryMultiplesAndAnnualizesQuarter() {
        var b = new PathB.Result("x", "X", 9.1e6, 6e6, 4e6, 0.5, 0.1, true, true, List.of());
        var r = PrelimValuation.compute("x", "Dexs", b, 1.0, 1e8, 1e8, peers());
        assertNotNull(r);
        assertEquals("Dexs", r.peerGroup());
        assertEquals(15.0, r.peerMultiples().get("median"), 1e-9);
        assertEquals(10.0, r.peerMultiples().get("p25"), 1e-9);
        assertEquals(3.65e7, r.annualRevenue(), 1e3);
        assertEquals(1.0, r.growth().get("base"), 1e-9, "两季度年化增速封顶 100%");
        assertEquals(3, r.scenarios().size());
        // 保守：收入不增长、10 倍 → 3.65 亿 / 1 亿流通 = 3.65x
        assertEquals(3.65, r.scenarios().get(0).multipleAt().get(12), 0.01);
        assertTrue(ReportWriterAccess.line(r).contains("现在买入（初步）"));
    }

    @Test
    void fallsBackToAllPeersWhenCategoryTooSmall() {
        var b = new PathB.Result("x", "X", 9.1e6, 6e6, 4e6, 0.5, 0.1, true, true, List.of());
        var r = PrelimValuation.compute("x", "Lending", b, 1.0, 1e8, 1e8, peers());
        assertEquals("全部协议", r.peerGroup());
        assertEquals(7, r.peerCount());
    }

    @Test
    void missingRevenueGivesNull() {
        var b = new PathB.Result("x", "X", null, 6e6, 4e6, null, 0.1, false, false, List.of());
        assertNull(PrelimValuation.compute("x", "Dexs", b, 1.0, 1e8, 1e8, peers()));
    }
}
