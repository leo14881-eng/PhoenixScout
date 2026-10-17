package com.phoenixscout.sim;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.scan.Pick;
import com.phoenixscout.support.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SimLedgerTest {

    static final LocalDate D0 = LocalDate.of(2026, 10, 17);

    static TreeMap<LocalDate, DailyPoint> h(double... closes) {
        TreeMap<LocalDate, DailyPoint> m = new TreeMap<>();
        for (int i = 0; i < closes.length; i++) m.put(D0.plusDays(i), new DailyPoint(D0.plusDays(i), closes[i], null, null));
        return m;
    }

    static Pick pick(String id, double price, double tp, double sl, double ratio) {
        return new Pick(id, id.toUpperCase(), "A", price, tp, sl, ratio, tp / price - 1, 1 - sl / price, "测试原因");
    }

    @Test
    void entryTakeProfitStopAndBreakeven(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = Map.of(
                "win", h(1.0, 1.2, 2.1),          // 第 2 天收盘 2.1 ≥ 目标 2 → 止盈
                "lose", h(1.0, 0.85),             // 第 1 天收盘 0.85 ≤ 防守 0.9 → 止损
                "be", h(1.0, 1.6, 0.99));         // 先涨 60% 防守上移到 1.0，再跌回 0.99 → 保本止损
        var ev0 = sim.run(D0, hist, List.of(pick("win", 1, 2, 0.8, 5), pick("lose", 1, 3, 0.9, 4), pick("be", 1, 3, 0.8, 3)));
        assertEquals(3, ev0.stream().filter(e -> e.kind().equals("入场")).count());
        assertEquals(0, sim.run(D0, hist, List.of(pick("win", 1, 2, 0.8, 5))).size(), "同一天不重复入场");

        var ev1 = sim.run(D0.plusDays(1), hist, List.of());
        assertTrue(ev1.stream().anyMatch(e -> e.kind().equals("止损") && e.coinId().equals("lose")));
        assertTrue(ev1.stream().anyMatch(e -> e.kind().equals("保本") && e.coinId().equals("be")));
        var ev2 = sim.run(D0.plusDays(2), hist, List.of());
        var win = ev2.stream().filter(e -> e.coinId().equals("win")).findFirst().orElseThrow();
        assertEquals("止盈", win.kind());
        // 1000×0.997/1 × 2.1 × 0.997 − 1000 = 1087.7
        assertEquals(1087.7, win.pnlUsd(), 0.5);
        assertTrue(ev2.stream().anyMatch(e -> e.coinId().equals("be") && e.kind().equals("保本止损")));

        var s = sim.summary();
        assertEquals(0, s.open());
        assertEquals(3, s.closed());
        assertEquals(1, s.wins());
        Path csv = dir.resolve("sim/ledger.csv");
        sim.exportCsv(csv);
        assertTrue(Files.readString(csv).contains("测试原因"));
    }

    @Test
    void capsOpenPositionsAndSkipsMissingClose(@TempDir Path dir) {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        List<Pick> picks = new ArrayList<>();
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = new java.util.HashMap<>();
        for (int i = 0; i < 15; i++) {
            picks.add(pick("c" + i, 1, 2, 0.8, 15 - i));
            hist.put("c" + i, h(1.0));     // 第二天没有收盘价
        }
        sim.run(D0, hist, picks);
        assertEquals(SimLedger.MAX_OPEN, sim.summary().open());
        assertTrue(sim.run(D0.plusDays(1), hist, List.of()).isEmpty(), "缺当天收盘价时不动作");
    }
}
