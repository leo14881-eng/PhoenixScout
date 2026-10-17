package com.phoenixscout.scan;

import com.phoenixscout.market.DailyPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ReturnsTest {

    static TreeMap<LocalDate, DailyPoint> series(LocalDate start, double... prices) {
        TreeMap<LocalDate, DailyPoint> h = new TreeMap<>();
        for (int i = 0; i < prices.length; i++) {
            LocalDate d = start.plusDays(i);
            h.put(d, new DailyPoint(d, Double.isNaN(prices[i]) ? null : prices[i], null, 100.0));
        }
        return h;
    }

    @Test
    void retUsesExactDaysOnly() {
        LocalDate d0 = LocalDate.of(2026, 1, 1);
        var h = series(d0, 100, Double.NaN, 150);
        assertEquals(0.5, Returns.ret(h, d0, d0.plusDays(2)).orElseThrow(), 1e-9);
        assertTrue(Returns.ret(h, d0, d0.plusDays(1)).isEmpty(), "缺失的一端必须返回空");
        assertTrue(Returns.ret(h, d0, d0.plusDays(5)).isEmpty(), "不得用邻近日期替代");
    }

    @Test
    void zeroOrNegativePriceIsMissing() {
        assertTrue(Returns.ret(0.0, 5.0).isEmpty());
        assertTrue(Returns.ret(null, 5.0).isEmpty());
    }

    @Test
    void medianOddEvenAndNulls() {
        assertEquals(2.0, Returns.median(List.of(3.0, 1.0, 2.0)).orElseThrow());
        assertEquals(2.5, Returns.median(List.of(4.0, 1.0, 2.0, 3.0)).orElseThrow());
        assertTrue(Returns.median(List.of()).isEmpty());
    }

    @Test
    void concentrationAndMinPrice() {
        LocalDate d0 = LocalDate.of(2026, 1, 1);
        double[] p = new double[30];
        for (int i = 0; i < 30; i++) p[i] = 100;
        p[10] = 200; // 一天翻倍后保持
        for (int i = 11; i < 30; i++) p[i] = 200;
        var h = series(d0, p);
        double c = Returns.top3Concentration(h, d0, d0.plusDays(29)).orElseThrow();
        assertEquals(1.0, c, 1e-9);
        assertEquals(100.0, Returns.minPrice(h, d0, d0.plusDays(29)).orElseThrow());
    }
}
