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

    static Pick pick(String id, double price, double tp1, Double tp2, double sl, double ratio) {
        return new Pick(id, id.toUpperCase(), "A", price, tp1, tp2, sl, ratio, tp1 / price - 1, 1 - sl / price, "测试原因");
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
        sim.run(D0, hist, List.of(pick("x", 1, 2, null, 0.65, 3)), Set.of(), List.of());
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
        sim.run(D0, hist, List.of(pick("lose", 1, 3, null, 0.65, 3), pick("be", 1, 5, null, 0.65, 3),
                pick("b", 1, 3, 6.0, 0.65, 3), pick("bad", 1, 3, null, 0.65, 3)), Set.of(), List.of());
        var ev = sim.run(D0.plusDays(2), hist, List.of(), Set.of("bad"), List.of());
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("lose") && e.kind().equals("止损")));
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("be") && e.kind().equals("保本止损")));
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("b") && e.kind().equals("长期目标止盈")));
        assertTrue(ev.stream().anyMatch(e -> e.coinId().equals("bad") && e.kind().equals("逻辑失效")));
    }

    @Test
    void portfolioSizingAndTrackRecordsEveryPick(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        var sim = new SimLedger(db.jdbc);
        List<Pick> picks = new ArrayList<>();
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            picks.add(pick("c" + i, 1, 2, null, 0.65, 15 - i));
            hist.put("c" + i, h(1.0));
        }
        sim.run(D0, hist, picks, Set.of(), List.of());
        assertEquals(15, sim.summary(SimLedger.TRACK).open(), "选币台账每个推荐都记");
        var p = sim.summary(SimLedger.PORTFOLIO);
        assertEquals(SimLedger.MAX_OPEN, p.open(), "组合最多 10 个");
        // 盈亏比 ≥10 的风险 125 美元，跌幅 35% → 357 美元；其余 100/0.35 = 286
        Double first = db.jdbc.queryForObject("SELECT amount_usd FROM sim_trade WHERE book='portfolio' AND coin_id='c0'", Double.class);
        assertEquals(357.1, first, 0.2);
        Double last = db.jdbc.queryForObject("SELECT amount_usd FROM sim_trade WHERE book='portfolio' AND coin_id='c9'", Double.class);
        assertEquals(285.7, last, 0.2);
        assertTrue(sim.run(D0, hist, picks, Set.of(), List.of()).isEmpty(), "同一天重复运行不重复入场");
        Path csv = dir.resolve("sim/ledger.csv");
        sim.exportCsv(csv);
        assertTrue(Files.readString(csv).contains("选币台账"));
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
        sim.run(D0, hist, List.of(pick("x", 1, 100, null, 0.65, 3)), Set.of(), alts);
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
        assertEquals(1000, SimLedger.size(10_000, 10_000, 25, 0.15).amount(), 1e-6);
        assertEquals(2000, SimLedger.size(10_000, 10_000, 12, 0.02).amount(), 1e-6);
        assertEquals(300, SimLedger.size(10_000, 300, 5, 0.10).amount(), 1e-6);
    }
}
