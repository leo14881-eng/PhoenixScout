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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class SimLedgerTest {

    static final LocalDate D0 = LocalDate.of(2026, 1, 1);

    static TreeMap<LocalDate, DailyPoint> h(double... closes) {
        TreeMap<LocalDate, DailyPoint> m = new TreeMap<>();
        for (int i = 0; i < closes.length; i++) m.put(D0.plusDays(i), new DailyPoint(D0.plusDays(i), closes[i], null, null));
        return m;
    }

    static Pick pick(String id, double price, Double tp1, Double tp2, double sl, Double ev) {
        return pick(id, price, tp1, tp2, sl, ev, "B");
    }

    static Pick pick(String id, double price, Double tp1, Double tp2, double sl, Double ev, String grade) {
        return new Pick(id, id.toUpperCase(), "A", grade, 3, true, price, tp1, tp2, sl, ev, tp1 == null ? null : tp1 / price - 1,
                1 - sl / price, "测试原因");
    }

    static double r(SimLedger.Event e) {
        return e.pnlUsd();
    }

    @Test
    void halfAtFirstTargetThenTrailingStop(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        // 入场 1.0，第一目标 2.0 卖一半；之后最高 4.0，回撤 40% 到 2.4 卖剩余
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = Map.of("x", h(1.0, 2.0, 4.0, 2.4), "bitcoin", h(1, 1, 1, 1));
        sim.run(D0, hist, List.of(pick("x", 1, 2.0, null, 0.65, 0.3)), Set.of(), List.of());
        var ev = sim.run(D0.plusDays(3), hist, List.of(), Set.of(), List.of());
        var half = ev.stream().filter(e -> e.kind().equals("止盈一半") && e.book().equals(SimLedger.TRACK)).findFirst().orElseThrow();
        var trail = ev.stream().filter(e -> e.kind().equals("回撤止盈") && e.book().equals(SimLedger.TRACK)).findFirst().orElseThrow();
        // 选币台账 1,000 美元：一半 500×0.997/1×2×0.997 − 500 = 494.0；剩余 498.5×2.4×0.997 − 500 = 692.8；合计 1186.8
        assertEquals(494.0, half.pnlUsd(), 0.5);
        assertEquals(1186.8, trail.pnlUsd(), 0.5);
        assertTrue(trail.note().contains("R"), trail.note());
        var s = sim.summary(SimLedger.TRACK);
        assertEquals(0, s.open());
        assertEquals(1, s.closed());
        assertEquals(1186.8, s.realized(), 0.5);
    }

    @Test
    void stopBreakevenLongTargetAndLogicInvalid(@TempDir Path dir) {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = Map.of(
                "lose", h(1.0, 0.6),               // 跌破防守 0.65 → 止损
                "be", h(1.0, 2.1, 0.99),           // 翻倍后防守上移到 1.0，再跌回 0.99 → 保本止损
                "b", h(1.0, 3.0, 6.0),             // 第一目标 3 卖一半，长期目标 6 卖剩余
                "bad", h(1.0, 1.1, 1.2));          // 研究判定失效 → 卖出
        sim.run(D0, hist, List.of(pick("lose", 1, 3.0, null, 0.65, 0.3), pick("be", 1, 5.0, null, 0.65, 0.3),
                pick("b", 1, 3.0, 6.0, 0.65, 0.3), pick("bad", 1, 3.0, null, 0.65, 0.3)), Set.of(), List.of());
        var ev = sim.run(D0.plusDays(2), hist, List.of(), Set.of("bad"), List.of());
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("lose") && e.kind().equals("止损")));
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("be") && e.kind().equals("保本止损")));
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("b") && e.kind().equals("长期目标止盈")));
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("bad") && e.kind().equals("逻辑失效")));
    }

    @Test
    void portfolioTwoSlotsQuarterlyAndTrackRecordsEveryPick(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = new HashMap<>();
        double[] flat = new double[120];
        java.util.Arrays.fill(flat, 1.0);
        for (String id : List.of("a", "b", "c", "d", "n")) hist.put(id, h(flat));
        hist.put("x", h(1.0, 0.5));
        List<Pick> picks = List.of(pick("n", 1, null, null, 0.65, null), pick("c", 1, 2.0, null, 0.65, 0.05),
                pick("b", 1, 2.0, null, 0.65, 0.20), pick("a", 1, 2.0, null, 0.65, -0.1, "A"));
        sim.run(D0, hist, picks, Set.of(), List.of());
        assertEquals(4, sim.summary(SimLedger.TRACK).open(), "选币台账每个推荐都记");
        var p = sim.summary(SimLedger.PORTFOLIO);
        assertEquals(2, p.open(), "组合最多 2 个");
        List<String> held = db.jdbc.queryForList("SELECT coin_id FROM sim_trade WHERE book='portfolio' ORDER BY id", String.class);
        assertEquals(List.of("a", "b"), held, "A 级优先，再按期望值；期望值缺失的 B 级不买");
        Double first = db.jdbc.queryForObject("SELECT amount_usd FROM sim_trade WHERE book='portfolio' AND coin_id='a'", Double.class);
        assertEquals(2500, first, 0.01, "总预算 5,000 美元等额分配");
        assertTrue(sim.run(D0, hist, picks, Set.of(), List.of()).isEmpty(), "同一天重复运行不重复入场");

        // 季度中途：卖出一个后，空位不补；到下个季度第一次扫描才补
        db.jdbc.update("UPDATE sim_trade SET status='closed', qty=0, pnl_usd=0, pnl_pct=0 WHERE book='portfolio' AND coin_id='a'");
        var mid = sim.run(D0.plusDays(10), hist, List.of(pick("d", 1, 2.0, null, 0.65, 0.5)), Set.of(), List.of());
        assertTrue(mid.stream().noneMatch(e -> e.kind().equals("入场")), "季度中途不补仓");
        var q2 = sim.run(LocalDate.of(2026, 4, 1), hist, List.of(pick("d", 1, 2.0, null, 0.65, 0.5)), Set.of(), List.of());
        assertTrue(q2.stream().anyMatch(e -> e.kind().equals("入场") && e.coinId().equals("d")), "季度复核时补仓");
        Path csv = dir.resolve("sim/ledger.csv");
        sim.exportCsv(csv);
        assertTrue(Files.readString(csv).contains("选币台账"));
    }

    @Test
    void noTargetUsesTrailingAfterDoubling(@TempDir Path dir) {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = Map.of("x", h(1.0, 1.5, 2.5, 1.4));
        sim.run(D0, hist, List.of(pick("x", 1, null, null, 0.65, null)), Set.of(), List.of());
        var ev = sim.run(D0.plusDays(3), hist, List.of(), Set.of(), List.of());
        assertTrue(ev.stream().anyMatch(e -> e.kind().equals("回撤止盈")), "无目标：翻倍后从最高 2.5 回撤 40% 到 1.4 卖出");
        assertTrue(ev.stream().noneMatch(e -> e.kind().equals("止盈一半")));
    }

    @Test
    void horizonsComparedWithBtcAndAlts(@TempDir Path dir) {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        double[] coin = new double[32], btc = new double[32], alt = new double[32];
        for (int i = 0; i < 32; i++) {
            coin[i] = 1 + i * 0.05;   // 30 天 +150%
            btc[i] = 1 + i * 0.01;    // +30%
            alt[i] = 1 + i * 0.02;    // +60%
        }
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = new HashMap<>();
        hist.put("x", h(coin));
        hist.put("bitcoin", h(btc));
        List<String> alts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            hist.put("a" + i, h(alt));
            alts.add("a" + i);
        }
        sim.run(D0, hist, List.of(pick("x", 1, 100.0, null, 0.65, 0.3)), Set.of(), alts);
        sim.run(D0.plusDays(31), hist, List.of(), Set.of(), alts);
        var st = new SimReview(db.jdbc).stats(SimLedger.TRACK);
        @SuppressWarnings("unchecked")
        var hz = (Map<String, Map<String, Object>>) st.get("horizons");
        var h30 = hz.get("30天");
        assertEquals(150.0, (Double) h30.get("avgReturnPct"), 0.1);
        assertEquals(100.0, (Double) h30.get("beatBtcPct"), 0.1);
        assertEquals(90.0, (Double) h30.get("excessVsAltPct"), 0.1);
        assertTrue(sim.coinsNeedingHistory(D0.plusDays(31)).contains("x"), "组合持仓每天需要日线");
    }

    @Test
    void sizing() {
        assertEquals(2500, SimLedger.size(5_000, 5_000, 2, 0.35).amount(), 1e-6);
        assertEquals(875, SimLedger.size(5_000, 5_000, 2, 0.35).risk(), 1e-6);
        assertEquals(1200, SimLedger.size(5_000, 1_200, 1, 0.35).amount(), 1e-6);
    }
}
