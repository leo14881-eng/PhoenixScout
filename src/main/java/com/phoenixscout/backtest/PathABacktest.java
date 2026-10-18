package com.phoenixscout.backtest;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.scan.DailyScanService;
import com.phoenixscout.scan.PathA;
import com.phoenixscout.scan.Pick;
import com.phoenixscout.scan.Returns;
import com.phoenixscout.scan.StrategyBacktest;
import com.phoenixscout.sim.SimLedger;
import com.phoenixscout.sim.SimReview;
import com.phoenixscout.snapshot.SnapshotService;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * 路径 A 历史回放（上线前对比用）：V2.6 收紧后的筛选 vs V2.5 原筛选 vs 随机选币，每天只用当天及以前的数据。
 * 比较入选数量、入选后 30/90/180 天收益与相对 BTC/ETH/山寨中位数的超额、胜率、回撤；并用同一套出场规则跑“选币台账”（每天前 5 个，每个 1,000 美元）。
 * 估值目标和质量分没有历史数据，回放里所有币都按“无法估值、B 级”处理（翻倍后启用回撤止盈）。
 *
 * 用法：java -cp ... com.phoenixscout.backtest.PathABacktest 数据库 快照 起始日 结束日 输出.md [随机次数]
 */
public final class PathABacktest {

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[0]);
        Path snap = Path.of(args[1]);
        LocalDate from = LocalDate.parse(args[2]), to = LocalDate.parse(args[3]);
        Path out = Path.of(args[4]);
        int randomRuns = args.length > 5 ? Integer.parseInt(args[5]) : 10;

        Map<String, TreeMap<LocalDate, DailyPoint>> hist = load(db);
        var st = new SnapshotService(snap.getParent()).load();
        if (!st.available()) throw new IllegalStateException(st.message());
        Set<String> excluded = new HashSet<>(List.of("bitcoin", "ethereum"));
        st.snapshot().coins().values().forEach(c -> { if (!c.excludedCategories().isEmpty()) excluded.add(c.id()); });
        List<String> histAlts = new ArrayList<>();
        for (String id : st.snapshot().historicalTopIds()) if (!excluded.contains(id)) histAlts.add(id);
        List<String> benchA = DailyScanService.bandSample(histAlts);
        LocalDate dataEnd = hist.get("bitcoin").lastKey();

        Map<LocalDate, List<StrategyBacktest.Signal>> v26 = new TreeMap<>(), v25 = new TreeMap<>();
        Map<LocalDate, List<StrategyBacktest.Signal>> eligibleByDay = new TreeMap<>();
        Map<LocalDate, List<String>> benchByDay = new HashMap<>();
        int v26Raw = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
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
            benchByDay.put(day, benchB);
            var bm = StrategyBacktest.benchmarks(hist, benchA, benchB, day);
            Double best30 = max(bm.btc30(), bm.eth30(), bm.benchB30()), best90 = max(bm.btc90(), bm.eth90(), bm.benchB90());
            List<StrategyBacktest.Signal> n = new ArrayList<>(), o = new ArrayList<>(), el = new ArrayList<>();
            for (String id : ranked) {
                double c = cap.get(id);
                if (c < StrategyBacktest.CAP_MIN || c > StrategyBacktest.CAP_MAX) continue;
                var h = hist.get(id);
                if (h.firstKey().isAfter(day.minusDays(120))) continue;
                double px = h.get(day).price();
                el.add(new StrategyBacktest.Signal(id, day, px, 0));
                Double r30 = Returns.rolling(h, day, 30).orElse(null), r90 = Returns.rolling(h, day, 90).orElse(null);
                if (r30 == null || r90 == null || best30 == null || best90 == null || r30 <= best30 || r90 <= best90) continue;
                var r = PathA.evaluate(id, h, day, Returns.price(h, StrategyBacktest.LONG_BASE).orElse(null),
                        Returns.price(h, day.minusDays(90)).orElse(null), bm);
                double strength = (r30 - best30) + (r90 - best90);
                // V2.5 原筛选：30/90 天跑赢三个基准（无余量）+ 长期弱势 + 没有更低低点
                if (r.longWeak() && !Boolean.FALSE.equals(r.higherLow())) o.add(new StrategyBacktest.Signal(id, day, px, strength));
                if (r.candidate()) n.add(new StrategyBacktest.Signal(id, day, px, strength));
            }
            n.sort(Comparator.comparingDouble(StrategyBacktest.Signal::strength).reversed());
            o.sort(Comparator.comparingDouble(StrategyBacktest.Signal::strength).reversed());
            v26Raw += n.size();
            if (n.size() > DailyScanService.A_CROWDED) n = new ArrayList<>(n.subList(0, DailyScanService.MAX_A_KEEP_WHEN_CROWDED));
            v26.put(day, n);
            v25.put(day, o);
            eligibleByDay.put(day, el);
        }

        StringBuilder s = new StringBuilder("# 路径 A 历史回放：V2.6 收紧筛选 vs V2.5 原筛选 vs 随机\n\n");
        s.append("- 回放区间：").append(from).append(" → ").append(to).append("，数据截止 ").append(dataEnd).append("；每天只用当天及以前的数据\n");
        s.append("- 样本：同一个币 30 天内重复入选只算第一次；入场价 = 入选当天 00:00 UTC 收盘价\n");
        s.append("- 随机对照：每天从同样市值区间、有 120 天历史的币里随机抽，数量与 V2.6 当天入选数相同，跑 ").append(randomRuns).append(" 次取中位数\n");
        s.append("- 偏差：币池只含 2026-10 仍在前 2,500 名的币（死币不在内，结果偏乐观）；只有一年数据，180 天样本很少\n\n");

        long days26 = v26.values().stream().filter(l -> !l.isEmpty()).count();
        s.append("## 入选数量\n\n| | 平均每天入选 | 最多一天 | 有入选的天数 |\n|---|---|---|---|\n");
        s.append(countRow("V2.5 原筛选", v25));
        s.append(countRow("V2.6 收紧后", v26));
        s.append(String.format(Locale.ROOT, "\nV2.6 截断前平均每天 %.1f 个（超过 30 个只保留前 20）。\n\n", v26Raw / (double) v26.size()));

        var rep26 = StrategyBacktest.report(sig(v26, benchByDay), hist, from, to, dataEnd);
        var rep25 = StrategyBacktest.report(sig(v25, benchByDay), hist, from, to, dataEnd);
        List<StrategyBacktest.Report> rnd = new ArrayList<>();
        for (int i = 0; i < randomRuns; i++) {
            Random rng = new Random(2000 + i);
            Map<LocalDate, List<StrategyBacktest.Signal>> rs = new TreeMap<>();
            for (var e : v26.entrySet()) {
                List<StrategyBacktest.Signal> pool = new ArrayList<>(eligibleByDay.get(e.getKey()));
                Collections.shuffle(pool, rng);
                rs.put(e.getKey(), pool.subList(0, Math.min(e.getValue().size(), pool.size())));
            }
            rnd.add(StrategyBacktest.report(sig(rs, benchByDay), hist, from, to, dataEnd));
        }
        s.append("## 入选后表现（每个样本等权平均）\n\n");
        for (int days : new int[]{30, 90, 180}) {
            s.append("### 入场后 ").append(days).append(" 天\n\n| | 样本 | 平均收益 | 胜率 | 相对 BTC | 相对 ETH | 相对山寨中位数 | 平均最大回撤 |\n|---|---|---|---|---|---|---|---|\n");
            s.append(hRow("V2.5 原筛选", rep25, days));
            s.append(hRow("V2.6 收紧后", rep26, days));
            s.append(hRowMedian("随机对照（中位数）", rnd, days));
            s.append("\n");
        }
        s.append(String.format(Locale.ROOT, "样本：V2.5 %d 个，V2.6 %d 个。归零或数据中断：V2.5 %.1f%%，V2.6 %.1f%%。V2.6 结论：%s\n\n",
                rep25.episodes(), rep26.episodes(), rep25.collapsedPct(), rep26.collapsedPct(), rep26.verdict()));

        // 选币台账：每天前 5 个（B 级观察上限），每个 1,000 美元，V2.6 出场规则
        s.append("## 选币台账回放（每天前 5 个，每个 1,000 美元，V2.6 出场规则）\n\n| | 记录笔数 | 合计盈亏 | 每笔平均 | 已平仓 | 止损占已平仓 | 平均 R | 中位数一笔 | 最好两笔 | 赚钱笔数占比 | 去掉最好两笔后合计 |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
        s.append(trackRow("V2.5 原筛选", track(hist, v25, benchByDay, from, to, null)));
        s.append(trackRow("V2.6 收紧后", track(hist, v26, benchByDay, from, to, null)));
        List<double[]> rt = new ArrayList<>();
        for (int i = 0; i < randomRuns; i++) {
            Random rng = new Random(3000 + i);
            Map<LocalDate, List<StrategyBacktest.Signal>> rs = new TreeMap<>();
            for (var e : v26.entrySet()) {
                List<StrategyBacktest.Signal> pool = new ArrayList<>(eligibleByDay.get(e.getKey()));
                Collections.shuffle(pool, rng);
                rs.put(e.getKey(), pool.subList(0, Math.min(Math.min(5, e.getValue().size()), pool.size())));
            }
            rt.add(track(hist, rs, benchByDay, from, to, rng));
        }
        rt.sort(Comparator.comparingDouble(x -> x[2]));
        double[] mid = rt.get(rt.size() / 2);
        s.append(String.format(Locale.ROOT, "| 随机对照（每笔平均的中位数那次） | %.0f | %+.0f | %+.0f | %.0f | %.0f%% | %.2f | %+.0f | %+.0f / %+.0f | %.0f%% | %+.0f |\n",
                mid[0], mid[1], mid[2], mid[3], mid[4], mid[5], mid[6], mid[7], mid[8], mid[9] * 100, mid[1] - mid[7] - mid[8]));
        s.append("\n持仓按回放最后一天收盘价估值。没有估值目标时不做“卖一半”，翻倍后从最高收盘回撤 40% 卖出，跌破防守价（−35% 或 90 天低点）止损。\n");
        Files.writeString(out, s.toString(), StandardCharsets.UTF_8);
        System.out.println(s);
    }

    static StrategyBacktest.Signals sig(Map<LocalDate, List<StrategyBacktest.Signal>> byDay, Map<LocalDate, List<String>> bench) {
        List<StrategyBacktest.Signal> eps = new ArrayList<>();
        Map<String, LocalDate> last = new HashMap<>();
        for (var e : byDay.entrySet()) {
            for (var x : e.getValue()) {
                LocalDate p = last.get(x.coinId());
                if (p == null || p.isBefore(e.getKey().minusDays(StrategyBacktest.DEDUPE_DAYS))) eps.add(x);
                last.put(x.coinId(), e.getKey());
            }
        }
        return new StrategyBacktest.Signals(byDay, eps, bench);
    }

    /** [笔数, 合计盈亏, 每笔平均, 已平仓, 止损占比, 平均R] */
    static double[] track(Map<String, TreeMap<LocalDate, DailyPoint>> hist, Map<LocalDate, List<StrategyBacktest.Signal>> byDay,
                          Map<LocalDate, List<String>> bench, LocalDate from, LocalDate to, Random unused) {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(ds).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SimLedger sim = new SimLedger(jdbc);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<Pick> picks = new ArrayList<>();
            for (var x : byDay.getOrDefault(d, List.of()).stream().limit(5).toList()) {
                var h = hist.get(x.coinId());
                double stop = Pick.stopFor(x.price(), Returns.minPrice(h, d.minusDays(89), d).orElse(null));
                picks.add(new Pick(x.coinId(), x.coinId(), "A", "B", null, false, x.price(), null, null, stop, null, null,
                        1 - stop / x.price(), "回放"));
            }
            sim.run(d, hist, picks, Set.of(), bench.getOrDefault(d, List.of()));
        }
        List<Double> per = new ArrayList<>();
        for (var r : jdbc.queryForList("SELECT COALESCE(pnl_usd, qty*last_price*0.997 - amount_usd*qty/qty_initial + realized_usd) p FROM sim_trade WHERE book='track'")) {
            per.add(((Number) r.get("p")).doubleValue());
        }
        Collections.sort(per);
        var t = sim.summary(SimLedger.TRACK);
        var st = new SimReview(jdbc).stats(SimLedger.TRACK);
        int n = t.open() + t.closed();
        double pnl = t.realized() + t.unrealized();
        return new double[]{n, pnl, n == 0 ? 0 : pnl / n, t.closed(), st.get("stopPctOfClosed") == null ? 0 : ((Number) st.get("stopPctOfClosed")).doubleValue(),
                st.get("avgR") == null ? 0 : ((Number) st.get("avgR")).doubleValue(),
                per.isEmpty() ? 0 : per.get(per.size() / 2), per.isEmpty() ? 0 : per.get(per.size() - 1),
                per.size() < 2 ? 0 : per.get(per.size() - 2), per.stream().filter(x -> x > 0).count() / (double) Math.max(1, per.size())};
    }

    static String trackRow(String name, double[] v) {
        return String.format(Locale.ROOT, "| %s | %.0f | %+.0f | %+.0f | %.0f | %.0f%% | %.2f | %+.0f | %+.0f / %+.0f | %.0f%% | %+.0f |\n", name, v[0], v[1], v[2], v[3], v[4], v[5],
                v[6], v[7], v[8], v[9] * 100, v[1] - v[7] - v[8]);
    }

    static String countRow(String name, Map<LocalDate, List<StrategyBacktest.Signal>> m) {
        double avg = m.values().stream().mapToInt(List::size).average().orElse(0);
        int max = m.values().stream().mapToInt(List::size).max().orElse(0);
        long days = m.values().stream().filter(l -> !l.isEmpty()).count();
        return String.format(Locale.ROOT, "| %s | %.1f | %d | %d / %d |\n", name, avg, max, days, m.size());
    }

    static String hRow(String name, StrategyBacktest.Report r, int days) {
        for (var h : r.horizons()) {
            if (h.days() != days) continue;
            return String.format(Locale.ROOT, "| %s | %d | %s | %.0f%% | %s | %s | %s | %s |\n", name, h.n(), p(h.avgReturn()), h.winRate() * 100,
                    p(h.excessBtc()), p(h.excessEth()), p(h.excessAlt()), p(h.avgMaxDrawdown()));
        }
        return "| " + name + " | 0 | — | — | — | — | — | — |\n";
    }

    static String hRowMedian(String name, List<StrategyBacktest.Report> rs, int days) {
        List<StrategyBacktest.HorizonStat> hs = new ArrayList<>();
        for (var r : rs) for (var h : r.horizons()) if (h.days() == days) hs.add(h);
        if (hs.isEmpty()) return "| " + name + " | 0 | — | — | — | — | — | — |\n";
        return String.format(Locale.ROOT, "| %s | %d | %s | %.0f%% | %s | %s | %s | %s |\n", name, (int) med(hs, h -> (double) h.n()),
                p(med(hs, StrategyBacktest.HorizonStat::avgReturn)), med(hs, StrategyBacktest.HorizonStat::winRate) * 100,
                p(med(hs, StrategyBacktest.HorizonStat::excessBtc)), p(med(hs, StrategyBacktest.HorizonStat::excessEth)),
                p(med(hs, StrategyBacktest.HorizonStat::excessAlt)), p(med(hs, StrategyBacktest.HorizonStat::avgMaxDrawdown)));
    }

    static double med(List<StrategyBacktest.HorizonStat> hs, java.util.function.Function<StrategyBacktest.HorizonStat, Double> f) {
        List<Double> v = new ArrayList<>();
        for (var h : hs) if (f.apply(h) != null) v.add(f.apply(h));
        Collections.sort(v);
        return v.isEmpty() ? Double.NaN : v.get(v.size() / 2);
    }

    static String p(Double v) {
        return v == null || v.isNaN() ? "—" : String.format(Locale.ROOT, "%+.1f%%", v * 100);
    }

    static Double max(Double... v) {
        Double m = null;
        for (Double x : v) {
            if (x == null) return null;
            m = m == null ? x : Math.max(m, x);
        }
        return m;
    }

    static Map<String, TreeMap<LocalDate, DailyPoint>> load(Path db) throws Exception {
        Map<String, TreeMap<LocalDate, DailyPoint>> out = new HashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             ResultSet rs = c.createStatement().executeQuery("SELECT coin_id, day, price, market_cap, volume FROM price_daily")) {
            while (rs.next()) {
                LocalDate d = LocalDate.parse(rs.getString(2));
                Double p = rs.getObject(3) == null ? null : rs.getDouble(3);
                Double m = rs.getObject(4) == null ? null : rs.getDouble(4);
                Double v = rs.getObject(5) == null ? null : rs.getDouble(5);
                out.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(d, new DailyPoint(d, p, m, v));
            }
        }
        return out;
    }
}
