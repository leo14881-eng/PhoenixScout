package com.phoenixscout.backtest;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.scan.DailyScanService;
import com.phoenixscout.scan.Returns;
import com.phoenixscout.snapshot.SnapshotService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 假设检验 H1（定义见 docs/假设检验_H1.md，事先登记，这里严格照写，不调参数）。
 * 用法：java -cp ... com.phoenixscout.backtest.HypothesisH1 数据库 快照 输出.md
 */
public final class HypothesisH1 {

    static final LocalDate BASE = LocalDate.of(2025, 10, 31);
    static final LocalDate DATA_START = LocalDate.of(2025, 10, 17), DATA_END = LocalDate.of(2026, 10, 17);
    static final LocalDate TRAIN_END = LocalDate.of(2026, 6, 16), TEST_START = LocalDate.of(2026, 6, 17);
    static final double CAP_MIN = 1e7, CAP_MAX = 3e9, MIN_VOL = 2e6, DROP = -0.65, COST = 0.006;
    static final int HOLD = 90, DEDUPE = 30, RANDOM_RUNS = 20;

    record Obs(String coin, LocalDate day, double ret, Double excess) { }

    public static void main(String[] args) throws Exception {
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = PathABacktest.load(Path.of(args[0]));
        var st = new SnapshotService(Path.of(args[1]).getParent()).load();
        if (!st.available()) throw new IllegalStateException(st.message());
        Set<String> excluded = new HashSet<>(List.of("bitcoin", "ethereum"));
        st.snapshot().coins().values().forEach(c -> { if (!c.excludedCategories().isEmpty()) excluded.add(c.id()); });
        var bh = hist.get("bitcoin");
        var eh = hist.get("ethereum");

        Map<LocalDate, List<String>> signals = new TreeMap<>(), pools = new TreeMap<>();
        Map<LocalDate, List<String>> bench = new HashMap<>();
        Map<String, LocalDate> last = new HashMap<>();
        for (LocalDate d = DATA_START.plusDays(30); !d.plusDays(HOLD).isAfter(DATA_END); d = d.plusDays(1)) {
            final LocalDate day = d;
            List<String> ranked = new ArrayList<>();
            Map<String, Double> cap = new HashMap<>();
            for (var e : hist.entrySet()) {
                if (excluded.contains(e.getKey())) continue;
                DailyPoint p = e.getValue().get(day);
                if (p == null || p.price() == null || p.marketCap() == null || p.marketCap() <= 0) continue;
                ranked.add(e.getKey());
                cap.put(e.getKey(), p.marketCap());
            }
            ranked.sort(Comparator.comparingDouble((String id) -> cap.get(id)).reversed());
            if (ranked.size() > 2500) ranked = new ArrayList<>(ranked.subList(0, 2500));
            List<String> benchB = DailyScanService.bandSample(ranked);
            bench.put(day, benchB);
            List<Double> b30 = new ArrayList<>();
            for (String id : benchB) Returns.rolling(hist.get(id), day, 30).ifPresent(b30::add);
            Double med30 = b30.size() * 2 >= benchB.size() && !benchB.isEmpty() ? Returns.median(b30).orElse(null) : null;
            Double btcLong = Returns.ret(bh, BASE, day).orElse(null), ethLong = Returns.ret(eh, BASE, day).orElse(null);
            List<String> pool = new ArrayList<>(), sig = new ArrayList<>();
            for (String id : ranked) {
                double c = cap.get(id);
                if (c < CAP_MIN || c > CAP_MAX) continue;
                var h = hist.get(id);
                Double rLong = Returns.ret(h, BASE, day).orElse(null);
                Double vol = Returns.avgVolume(h, day.minusDays(29), day).orElse(null);
                if (rLong == null || vol == null || vol < MIN_VOL) continue;
                pool.add(id);
                Double r30 = Returns.rolling(h, day, 30).orElse(null);
                if (med30 == null || btcLong == null || ethLong == null || r30 == null) continue;
                if (rLong <= DROP && rLong < btcLong && rLong < ethLong && r30 < med30) {
                    LocalDate prev = last.get(id);
                    last.put(id, day);
                    if (prev != null && !prev.isBefore(day.minusDays(DEDUPE))) continue;
                    sig.add(id);
                }
            }
            signals.put(day, sig);
            pools.put(day, pool);
        }

        StringBuilder s = new StringBuilder("# 假设检验 H1 结果\n\n定义和判定标准见 `docs/假设检验_H1.md`（运行前已登记，提交 8e18ae0）。本次严格按登记的定义运行，没有调整任何参数。\n\n");
        Map<String, Object[]> res = new java.util.LinkedHashMap<>();
        for (String period : List.of("参照期（前 8 个月）", "检验期（后 4 个月）")) {
            boolean test = period.startsWith("检验");
            List<Obs> obs = new ArrayList<>();
            for (var e : signals.entrySet()) if (inPeriod(e.getKey(), test)) for (String id : e.getValue()) obs.add(outcome(hist, bench, id, e.getKey()));
            obs.removeIf(o -> Double.isNaN(o.ret()));
            List<Double> randMeans = new ArrayList<>();
            for (int i = 0; i < RANDOM_RUNS; i++) {
                Random rng = new Random(7000 + i);
                List<Double> r = new ArrayList<>();
                for (var e : signals.entrySet()) {
                    if (!inPeriod(e.getKey(), test) || e.getValue().isEmpty()) continue;
                    List<String> pool = new ArrayList<>(pools.get(e.getKey()));
                    Collections.shuffle(pool, rng);
                    for (String id : pool.subList(0, Math.min(e.getValue().size(), pool.size()))) {
                        var o = outcome(hist, bench, id, e.getKey());
                        if (!Double.isNaN(o.ret())) r.add(o.ret());
                    }
                }
                randMeans.add(avg(r));
            }
            Collections.sort(randMeans);
            res.put(period, new Object[]{obs, randMeans.isEmpty() ? Double.NaN : randMeans.get(randMeans.size() / 2)});
        }
        s.append("| 区间 | 信号日期范围 | 样本 | 平均收益 | 收益中位数 | 胜率 | 平均超额 | 超额中位数 | 随机对照平均收益（20 次中位数） |\n|---|---|---|---|---|---|---|---|---|\n");
        String verdict = null;
        for (var e : res.entrySet()) {
            @SuppressWarnings("unchecked") List<Obs> obs = (List<Obs>) e.getValue()[0];
            double rand = (double) e.getValue()[1];
            List<Double> ret = obs.stream().map(Obs::ret).toList();
            List<Double> ex = obs.stream().map(Obs::excess).filter(java.util.Objects::nonNull).toList();
            boolean test = e.getKey().startsWith("检验");
            s.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s | %s | %s |\n", e.getKey(),
                    test ? TEST_START + " → " + DATA_END.minusDays(HOLD) : DATA_START.plusDays(30) + " → " + TRAIN_END.minusDays(HOLD),
                    obs.size(), p(avg(ret)), p(med(ret)), obs.isEmpty() ? "—" : String.format(Locale.ROOT, "%.0f%%", ret.stream().filter(x -> x > 0).count() * 100.0 / ret.size()),
                    p(avg(ex)), p(med(ex)), p(rand)));
            if (test) {
                if (obs.size() < 30) verdict = "样本不足，无法判断（检验期只有 " + obs.size() + " 个样本，需 ≥30）";
                else {
                    boolean c2 = avg(ex) > 0, c3 = med(ex) > 0, c4 = avg(ret) > rand;
                    verdict = (c2 && c3 && c4 ? "通过（初步有效，不能用于真实买入）" : "未通过")
                            + String.format("：平均超额 > 0 %s，超额中位数 > 0 %s，高于随机对照 %s", c2 ? "✓" : "✗", c3 ? "✓" : "✗", c4 ? "✓" : "✗");
                }
            }
        }
        s.append("\n**判定（只看检验期）：").append(verdict).append("**\n\n");
        s.append("收益已扣 0.6% 往返成本。超额 = 该币 90 天收益 − 同一天山寨中位数成分的 90 天收益中位数。");
        s.append("偏差：币池只含 2026-10 仍在前 2,500 名的币，死币不在内，对“超跌”类信号偏乐观。365 天数据只覆盖一种市场环境。\n");
        Files.writeString(Path.of(args[2]), s.toString(), StandardCharsets.UTF_8);
        System.out.println(s);
    }

    static boolean inPeriod(LocalDate day, boolean test) {
        LocalDate end = day.plusDays(HOLD);
        return test ? !day.isBefore(TEST_START) && !end.isAfter(DATA_END) : !end.isAfter(TRAIN_END);
    }

    static Obs outcome(Map<String, TreeMap<LocalDate, DailyPoint>> hist, Map<LocalDate, List<String>> bench, String id, LocalDate day) {
        LocalDate end = day.plusDays(HOLD);
        var r = Returns.ret(hist.get(id), day, end);
        if (r.isEmpty()) return new Obs(id, day, Double.NaN, null);
        double ret = (1 + r.get()) * (1 - COST) - 1;
        List<Double> alts = new ArrayList<>();
        for (String a : bench.getOrDefault(day, List.of())) Returns.ret(hist.get(a), day, end).ifPresent(alts::add);
        Double ex = alts.size() >= 10 ? ret - Returns.median(alts).orElseThrow() : null;
        return new Obs(id, day, ret, ex);
    }

    static double avg(List<Double> v) {
        return v.isEmpty() ? Double.NaN : v.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    static double med(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        return c.get(c.size() / 2);
    }

    static String p(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%+.1f%%", v * 100);
    }
}
