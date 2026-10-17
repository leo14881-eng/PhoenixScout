package com.phoenixscout.scan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PrelimValuationTest {

    static List<PrelimValuation.Peer> peers() {
        List<PrelimValuation.Peer> p = new ArrayList<>();
        // 同类 Dexs：倍数 4..32（8 个），收入 200 万
        for (int m : new int[]{4, 8, 12, 16, 20, 24, 28, 32}) p.add(new PrelimValuation.Peer("Dexs", m * 2e6, 2e6));
        // 噪声：收入太小、倍数离谱，必须被过滤
        p.add(new PrelimValuation.Peer("Dexs", 5e9, 1e4));
        p.add(new PrelimValuation.Peer("Dexs", 1e10, 2e6));
        for (int m : new int[]{50, 60}) p.add(new PrelimValuation.Peer("Lending", m * 2e6, 2e6));
        return p;
    }

    static PathB.Result b() {
        return new PathB.Result("x", "X", 9.1e6, 6e6, 4e6, 0.5, 0.1, true, true, List.of());
    }

    @Test
    void filtersNoiseAndUsesSameCategory() {
        var r = PrelimValuation.compute("x", "Dexs", b(), 1.0, 3.65e8, 3.65e8, peers());
        assertNotNull(r);
        assertEquals("Dexs", r.peerGroup());
        assertEquals(8, r.peerCount(), "收入过小和倍数超过 300 的样本被去掉");
        assertEquals(18.0, r.peerMultiples().get("median"), 1e-9);
        assertEquals(10.0, r.currentMultiple(), 1e-6);
        assertEquals(0.5, r.growth().get("base"), 1e-9, "基准增速封顶 50%");
        assertEquals(1.0, r.growth().get("optimistic"), 1e-9);
    }

    @Test
    void conservativeNeverAboveOneAndNoOvernightRerating() {
        var r = PrelimValuation.compute("x", "Dexs", b(), 1.0, 3.65e8, 3.65e8, peers());
        var cons = r.scenarios().get(0);
        cons.multipleAt().values().forEach(v -> assertTrue(v <= 1.0 + 1e-9, "保守情景只压缩不扩张"));
        var base = r.scenarios().get(1);
        assertTrue(base.multipleAt().get(6) < 1.6, "倍数逐步趋向目标，6 个月内不会暴涨");
        // 36 个月：收入 ×1.5^3 = 3.375，倍数 10→18 → 6.075x
        assertEquals(6.075, base.multipleAt().get(36), 0.01);
    }

    @Test
    void chainCategorySkippedAndTooFewPeersNull() {
        assertNull(PrelimValuation.compute("x", "Chain", b(), 1.0, 1e8, 1e8, peers()));
        assertNull(PrelimValuation.compute("x", "Dexs", b(), 1.0, 1e8, 1e8, peers().subList(0, 5)));
    }

    @Test
    void lineWarnsWhenFarBelowPeers() {
        var r = PrelimValuation.compute("x", "Dexs", b(), 1.0, 3.65e7, 3.65e7, peers());   // 当前 1 倍
        assertTrue(ReportWriterAccess.line(r).contains("收入可能并不归代币持有人"));
    }
}
