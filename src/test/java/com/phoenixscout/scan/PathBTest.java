package com.phoenixscout.scan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class PathBTest {

    static final LocalDate LATEST = LocalDate.of(2026, 10, 16);

    static TreeMap<LocalDate, Double> rev(double q2, double q1, double q0) {
        TreeMap<LocalDate, Double> m = new TreeMap<>();
        for (int i = 0; i < 273; i++) {
            LocalDate d = LATEST.minusDays(i);
            double v = i < 91 ? q0 : i < 182 ? q1 : q2;
            m.put(d, v);
        }
        return m;
    }

    @Test
    void twoConsecutiveQuarterGrowthPasses() {
        var r = PathB.evaluate("c", "p", rev(100, 150, 300), LATEST, 0.10);
        assertTrue(r.pass());
        assertEquals(1.0, r.revenueGrowth(), 1e-9);
        assertTrue(r.priceLagging(), "收入翻倍而币价只涨 10%");
    }

    @Test
    void declineFails() {
        assertFalse(PathB.evaluate("c", "p", rev(300, 200, 250), LATEST, 0.0).pass());
    }

    @Test
    void insufficientDataIsNotPass() {
        TreeMap<LocalDate, Double> m = new TreeMap<>();
        m.put(LATEST, 5.0);
        var r = PathB.evaluate("c", "p", m, LATEST, 0.0);
        assertFalse(r.pass());
        assertNull(r.q1());
    }
}
