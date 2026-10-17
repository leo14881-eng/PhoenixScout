package com.phoenixscout.scan;

import com.phoenixscout.market.DailyPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PathATest {

    static final LocalDate LATEST = LocalDate.of(2026, 10, 17);

    /** 线性从 p0 走到 p1（覆盖最近 400 天）。 */
    static TreeMap<LocalDate, DailyPoint> path(double p0, double pMid, double p1) {
        TreeMap<LocalDate, DailyPoint> h = new TreeMap<>();
        int n = 400;
        for (int i = 0; i <= n; i++) {
            LocalDate d = LATEST.minusDays(n - i);
            double v = i < n - 90 ? p0 + (pMid - p0) * i / (n - 90.0) : pMid + (p1 - pMid) * (i - (n - 90.0)) / 90.0;
            h.put(d, new DailyPoint(d, v, v * 1e6, 1000.0));
        }
        return h;
    }

    static PathA.Benchmarks bm() {
        return new PathA.Benchmarks(-0.40, -0.50, -0.55, 0.27, 0.27, 0.10,
                0.05, 0.04, 0.02, 0.10, 0.12, 0.05, 0.0, 0.0, -0.20, -0.30);
    }

    @Test
    void strongReboundAfterCrashIsCandidate() {
        var h = path(10, 2, 5);  // 跌 80% 后最近 90 天涨 150%
        var r = PathA.evaluate("x", h, LATEST, 10.0, 2.0, bm());
        assertTrue(r.longWeak());
        assertEquals(Boolean.TRUE, r.a2Pass());
        assertTrue(r.candidate());
        assertTrue(r.drop65to90());
        assertEquals(Boolean.TRUE, r.a1LongOk());
    }

    @Test
    void lowerLowIsBearRallyNotCandidate() {
        var h = path(10, 2, 5);
        var d = LATEST.minusDays(25);
        h.put(d, new DailyPoint(d, 1.0, 1e6, 1000.0));          // 近 30 天跌破前低
        h.put(LATEST, new DailyPoint(LATEST, 6.0, 6e6, 1000.0));
        var r = PathA.evaluate("x", h, LATEST, 10.0, 2.0, bm());
        assertEquals(Boolean.TRUE, r.a2Pass());
        assertEquals(Boolean.FALSE, r.higherLow());
        assertFalse(r.candidate(), "更低的低点视为熊市反弹");
    }

    @Test
    void a1LongIsSingleStandard() {
        // 跌 66%：跑输 BTC(-40%)/ETH(-50%) 且 ≥65% → 满足（旧规则要求低于 -70% 会被排除）
        var r = PathA.evaluate("x", path(10, 3.4, 8), LATEST, 10.0, 3.4, bm());
        assertEquals(Boolean.TRUE, r.a1LongOk());
        var r2 = PathA.evaluate("x", path(10, 4, 8), LATEST, 10.0, 4.0, bm());
        assertEquals(Boolean.FALSE, r2.a1LongOk(), "跌 60% 不满足 65%");
    }

    @Test
    void weakCoinIsNotCandidate() {
        var h = path(10, 2, 1.9);
        var r = PathA.evaluate("x", h, LATEST, 10.0, 2.0, bm());
        assertEquals(Boolean.FALSE, r.a2Pass());
        assertFalse(r.candidate());
    }

    @Test
    void missingBenchmarkMeansUndecidedNotFail() {
        var b = new PathA.Benchmarks(-0.40, -0.50, null, 0.27, 0.27, null,
                0.05, 0.04, null, 0.10, 0.12, null, 0.0, 0.0, -0.2, -0.3);
        var r = PathA.evaluate("x", path(10, 2, 5), LATEST, 10.0, 2.0, b);
        assertNull(r.a2Pass(), "基准 B 缺失时 A2 必须是未判定");
        assertNull(r.a1RecentOk());
        assertFalse(r.candidate());
    }

    @Test
    void notInSnapshotFallsBackTo365() {
        var r = PathA.evaluate("x", path(10, 2, 5), LATEST, null, null, bm());
        assertNull(r.rLong());
        assertTrue(r.longWeak(), "365 天收益明显跑输 BTC/ETH");
        assertFalse(r.notes().isEmpty());
    }
}
