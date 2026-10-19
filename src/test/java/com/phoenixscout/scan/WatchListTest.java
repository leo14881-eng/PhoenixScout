package com.phoenixscout.scan;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.support.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class WatchListTest {

    @Test
    void arbIsOnTheList() {
        var e = WatchList.load().stream().filter(x -> x.coinId().equals("arbitrum")).findFirst().orElseThrow();
        assertTrue(e.revenueSlugs().contains("arbitrum"));
        assertEquals(0.10, e.partners().get(0).share(), 1e-9);
    }

    @Test
    void signalsFireOnlyWhenTheyTurnTrue(@TempDir Path dir) {
        var db = new TestDb(dir);
        var e = new WatchList.Entry("x", "X", "2026-10-20", "测试", List.of("x-chain"),
                List.of(new WatchList.Partner("partner", "P", 0.10)), List.of());
        LocalDate d0 = LocalDate.of(2026, 10, 20);
        TreeMap<LocalDate, DailyPoint> h = new TreeMap<>();
        for (int i = 0; i < 200; i++) h.put(d0.minusDays(100).plusDays(i), new DailyPoint(d0.minusDays(100).plusDays(i), 1.0, 1e8, 1e6));

        // 第一天：近 30 天 10+1 > 前 30 天 5+0.5，但没有 30 天前的记录 → 待确认
        var s1 = WatchList.evaluate(db.jdbc, e, d0, null, h,
                Map.of("x-chain", new double[]{10, 5}, "partner", new double[]{10, 5}), Map.of());
        assertNull(s1.revenueGrowing());
        assertEquals(11, s1.total30(), 1e-9);
        assertTrue(s1.newlyTriggered().isEmpty());

        // 30 天后：20+2 > 15+1.5，且上一期 11 > 5.5 → 连续增长，首次满足 → 提醒
        var s2 = WatchList.evaluate(db.jdbc, e, d0.plusDays(30), null, h,
                Map.of("x-chain", new double[]{20, 15}, "partner", new double[]{20, 15}), Map.of());
        assertEquals(Boolean.TRUE, s2.revenueGrowing());
        assertEquals(List.of("分成收入连续增长"), s2.newlyTriggered());

        // 第二天仍满足 → 不重复提醒；持币人收入首次出现 → 提醒
        var s3 = WatchList.evaluate(db.jdbc, e, d0.plusDays(31), null, h,
                Map.of("x-chain", new double[]{20, 15}, "partner", new double[]{20, 15}), Map.of("x-chain", new double[]{3, 0}));
        assertEquals(List.of("持币人收入出现"), s3.newlyTriggered());

        // 收入下降 → 不满足
        var s4 = WatchList.evaluate(db.jdbc, e, d0.plusDays(32), null, h,
                Map.of("x-chain", new double[]{5, 15}), Map.of());
        assertEquals(Boolean.FALSE, s4.revenueGrowing());
        assertTrue(WatchList.line(s4).contains("下降"));
    }
}
