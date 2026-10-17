package com.phoenixscout.backtest;

import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.scan.DailyScanService;
import com.phoenixscout.scan.PathA;
import com.phoenixscout.scan.Pick;
import com.phoenixscout.scan.Returns;
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
 * 路径 A 历史回放（V2.5 长线规则）：按日“假装运行”，每天只用当天及以前的数据选推荐、模拟入场与止盈止损，
 * 并用同一套出场规则跑“随机选币”对照组（多次），判断推荐是否比随便选更好。
 *
 * <p>避免未来数据：长期区间 = 2025-10-31 → 回放当天前 90 天（生产用固定 2026-07-31，回放时不能用未来日期）；
 * 排名用当天市值；基准、上涨质量都只用当天及以前的日线。
 * <p>已知偏差：币池是“归档时（2026-10）还在前 2,500 名”的币，已经死掉的币不在里面 → 结果偏乐观；只有一年数据，长线目标看不完。
 *
 * 用法：java -cp ... com.phoenixscout.backtest.PathABacktest 数据库 快照 起始日 结束日 输出.md [随机次数]
 */
public final class PathABacktest {

    static final LocalDate BASE = LocalDate.of(2025, 10, 31);
    static final double CAP_MIN = 3e7, CAP_MAX = 1e9;

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[0]);
        Path snap = Path.of(args[1]);
        LocalDate from = LocalDate.parse(args[2]), to = LocalDate.parse(args[3]);
        Path out = Path.of(args[4]);
        int randomRuns = args.length > 5 ? Integer.parseInt(args[5]) : 20;

        Map<String, TreeMap<LocalDate, DailyPoint>> hist = load(db);
        var st = new SnapshotService(snap.getParent()).load();
        if (!st.available()) throw new IllegalStateException(st.message());
        Set<String> excluded = new HashSet<>(List.of("bitcoin", "ethereum"));
        st.snapshot().coins().values().forEach(c -> { if (!c.excludedCategories().isEmpty()) excluded.add(c.id()); });
        List<String> histAlts = new ArrayList<>();
        for (String id : st.snapshot().historicalTopIds()) if (!excluded.contains(id)) histAlts.add(id);
        List<String> benchA = bandSample(histAlts);

        Map<LocalDate, Integer> counts = new HashMap<>();
        Result model = run(hist, excluded, benchA, from, to, null, counts);
        List<Result> randoms = new ArrayList<>();
        for (int i = 0; i < randomRuns; i++) randoms.add(run(hist, excluded, benchA, from, to, new Random(1000 + i), counts));
        Files.writeString(out, report(model, randoms, from, to, hist), StandardCharsets.UTF_8);
        System.out.println(Files.readString(out));
    }

    record Result(Map<String, Object> track, Map<String, Object> portfolio, double portfolioEquity, double trackPnl,
                  int trackTrades, int days, List<String> sampleTrades) { }

    static Result run(Map<String, TreeMap<LocalDate, DailyPoint>> hist, Set<String> excluded, List<String> benchA,
                      LocalDate from, LocalDate to, Random random,
                      Map<LocalDate, Integer> counts) throws Exception {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(ds).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SimLedger sim = new SimLedger(jdbc);
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            days++;
            final LocalDate day = d;
            // 当天山寨排名（按当天市值）
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
            LocalDate mid = day.minusDays(90);
            var bh = hist.get("bitcoin");
            var eh = hist.get("ethereum");
            List<Double> aLong = new ArrayList<>(), aRecent = new ArrayList<>(), b30 = new ArrayList<>(), b90 = new ArrayList<>();
            for (String id : benchA) {
                Returns.ret(hist.get(id), BASE, mid).ifPresent(aLong::add);
                Returns.ret(hist.get(id), mid, day).ifPresent(aRecent::add);
            }
            for (String id : benchB) {
                Returns.rolling(hist.get(id), day, 30).ifPresent(b30::add);
                Returns.rolling(hist.get(id), day, 90).ifPresent(b90::add);
            }
            var bm = new PathA.Benchmarks(Returns.ret(bh, BASE, mid).orElse(null), Returns.ret(eh, BASE, mid).orElse(null),
                    Returns.median(aLong).orElse(null), Returns.ret(bh, mid, day).orElse(null), Returns.ret(eh, mid, day).orElse(null),
                    Returns.median(aRecent).orElse(null), Returns.rolling(bh, day, 30).orElse(null), Returns.rolling(eh, day, 30).orElse(null),
                    Returns.median(b30).orElse(null), Returns.rolling(bh, day, 90).orElse(null), Returns.rolling(eh, day, 90).orElse(null),
                    Returns.median(b90).orElse(null), Returns.rolling(bh, day, 180).orElse(null), Returns.rolling(eh, day, 180).orElse(null),
                    Returns.rolling(bh, day, 365).orElse(null), Returns.rolling(eh, day, 365).orElse(null));
            List<Pick> cands = new ArrayList<>();
            List<Pick> anyEligible = new ArrayList<>();
            for (String id : ranked) {
                double c = cap.get(id);
                if (c < CAP_MIN / 3 || c > CAP_MAX * 3) continue;
                var h = hist.get(id);
                if (h.firstKey().isAfter(day.minusDays(90))) continue;
                Double price = Returns.price(h, day).orElse(null);
                Double base = Returns.price(h, BASE).orElse(null);
                if (price == null || base == null) continue;
                double up = base / price - 1;
                if (up <= 0.10) continue;
                Double low90 = Returns.minPrice(h, day.minusDays(89), day).orElse(null);
                double sl = Pick.stopFor(price, low90);
                double down = 1 - sl / price;
                Pick pk = new Pick(id, id, "A", price, base, null, sl, up / down, up, down, random == null ? "回放：超跌转强" : "回放：随机对照");
                anyEligible.add(pk);
                if (random != null) continue;
                var r = PathA.evaluate(id, h, day, base, Returns.price(h, mid).orElse(null), bm);
                if (r.candidate()) cands.add(pk);
            }
            List<Pick> picks;
            if (random == null) {
                cands.sort(Comparator.comparingDouble(Pick::ratio).reversed());
                picks = cands.size() > 10 ? cands.subList(0, 10) : cands;
                counts.put(day, picks.size());
            } else {
                // 对照组：从同样“市值区间 + 有长期目标空间”的币里，每天随机选与模型当天同等数量
                Collections.shuffle(anyEligible, random);
                int k = Math.min(counts.getOrDefault(day, 0), anyEligible.size());
                picks = anyEligible.subList(0, k);
            }
            sim.run(day, hist, picks, Set.of(), benchB);
        }
        var review = new SimReview(jdbc);
        var p = sim.summary(SimLedger.PORTFOLIO);
        var t = sim.summary(SimLedger.TRACK);
        List<String> sample = jdbc.queryForList("SELECT coin_id || ' ' || opened_day || ' → ' || COALESCE(exit_reason,'持有') || ' '"
                + " || printf('%+.0f', COALESCE(pnl_usd, (qty*last_price*0.997 - amount_usd*qty/qty_initial + realized_usd)))"
                + " FROM sim_trade WHERE book='portfolio' ORDER BY id LIMIT 40", String.class);
        return new Result(review.stats(SimLedger.TRACK), review.stats(SimLedger.PORTFOLIO), p.equity() + p.unrealized(),
                t.realized() + t.unrealized(), (int) t.open() + t.closed(), days, sample);
    }

    static List<String> bandSample(List<String> ranked) {
        return DailyScanService.bandSample(ranked);
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

    @SuppressWarnings("unchecked")
    static String report(Result m, List<Result> rnd, LocalDate from, LocalDate to, Map<String, TreeMap<LocalDate, DailyPoint>> hist) throws Exception {
        StringBuilder s = new StringBuilder("# 路径 A 历史回放（V2.5 长线规则）\n\n");
        s.append("- 回放区间：").append(from).append(" → ").append(to).append("（").append(m.days()).append(" 天），每天只用当天及以前的数据\n");
        s.append("- 对照组：同样市值区间、同样有“回到 2025-10-31 价”空间的币里每天随机选与模型当天同样数量，出场规则相同，跑 ").append(rnd.size()).append(" 次\n");
        s.append("- 偏差：币池只含 2026-10 仍在前 2,500 名的币（死币不在内，结果偏乐观）；只有一年数据，长线目标大多还没走完，持仓按回放最后一天收盘价估值\n\n");
        var bh = hist.get("bitcoin");
        Double btc = Returns.ret(bh, from, to).orElse(null);
        s.append("## 模拟组合（本金 10,000 美元）\n\n| | 期末权益 | 收益 | 最大回撤 | 已平仓 | 平均 R | 第一目标命中 |\n|---|---|---|---|---|---|---|\n");
        s.append(row("模型（推荐）", m.portfolioEquity(), m.portfolio()));
        List<Double> rq = new ArrayList<>();
        for (Result r : rnd) rq.add(r.portfolioEquity());
        Collections.sort(rq);
        double rmed = rq.get(rq.size() / 2);
        s.append(String.format(Locale.ROOT, "| 随机对照（中位数，%d 次） | %.0f | %+.1f%% | — | — | — | — |\n", rnd.size(), rmed, rmed / 100 - 100));
        s.append(String.format(Locale.ROOT, "| 随机对照（最差/最好） | %.0f / %.0f | %+.1f%% / %+.1f%% | | | | |\n", rq.get(0), rq.get(rq.size() - 1),
                rq.get(0) / 100 - 100, rq.get(rq.size() - 1) / 100 - 100));
        if (btc != null) s.append(String.format(Locale.ROOT, "| 同期持有 BTC | %.0f | %+.1f%% | | | | |\n", 10000 * (1 + btc), btc * 100));
        long beat = rq.stream().filter(x -> x < m.portfolioEquity()).count();
        s.append(String.format(Locale.ROOT, "\n模型期末权益高于 %d/%d 次随机对照。\n\n", beat, rnd.size()));

        s.append("## 选币台账（每个推荐等额 1,000 美元）\n\n");
        Map<String, Object> t = m.track();
        s.append(String.format(Locale.ROOT, "记录 %s 笔，已平仓 %s 笔，第一目标命中 %s%%，止损占已平仓 %s%%，平均 %sR，合计盈亏 %+.0f 美元\n\n",
                t.get("trades"), t.get("closed"), t.get("tp1HitPct"), t.get("stopPctOfClosed"), t.get("avgR"), m.trackPnl()));
        List<Double> rt = new ArrayList<>();
        for (Result r : rnd) rt.add(r.trackPnl() / Math.max(1, r.trackTrades()));
        Collections.sort(rt);
        s.append(String.format(Locale.ROOT, "每笔平均盈亏：模型 %+.0f 美元；随机对照中位数 %+.0f 美元（最差 %+.0f，最好 %+.0f）\n\n",
                m.trackPnl() / Math.max(1, m.trackTrades()), rt.get(rt.size() / 2), rt.get(0), rt.get(rt.size() - 1)));
        var hz = (Map<String, Map<String, Object>>) t.get("horizons");
        if (!hz.isEmpty()) {
            s.append("| 入场后 | 笔数 | 平均收益 | 跑赢 BTC | 相对山寨中位数 |\n|---|---|---|---|---|\n");
            hz.forEach((k, o) -> s.append("| ").append(k).append(" | ").append(o.get("n")).append(" | ").append(o.get("avgReturnPct"))
                    .append("% | ").append(o.get("beatBtcPct")).append("% | ").append(o.get("excessVsAltPct")).append("% |\n"));
        }
        s.append("\n## 模拟组合前 40 笔\n\n");
        m.sampleTrades().forEach(x -> s.append("- ").append(x).append("\n"));
        return s.toString();
    }

    static String row(String name, double eq, Map<String, Object> st) {
        return String.format(Locale.ROOT, "| %s | %.0f | %+.1f%% | %s%% | %s | %s | %s%% |\n", name, eq, eq / 100 - 100,
                st.get("maxDrawdownPct"), st.get("closed"), st.get("avgR"), st.get("tp1HitPct"));
    }
}
