package com.phoenixscout.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.phoenixscout.config.PsProperties;
import com.phoenixscout.llama.DefiLlamaClient;
import com.phoenixscout.market.ApiException;
import com.phoenixscout.market.CoinGeckoClient;
import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.market.MarketCoin;
import com.phoenixscout.notify.TelegramNotifier;
import com.phoenixscout.report.ReportWriter;
import com.phoenixscout.snapshot.SnapshotService;
import com.phoenixscout.store.PriceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每日扫描（第二十二条）。请求策略：
 * 全市场行情用批量接口（/coins/markets 每次 250 个，共 10 次）；排除分类 7 天缓存；
 * 精确日线只对需要的币（BTC/ETH、基准成分、预筛候选、路径 B 协议代币）做增量补齐（只补缺的天数），
 * 历史日线来自本地 SQLite（P0 归档种子 + 每日增量），不重复请求。
 */
public class DailyScanService {

    private static final Logger log = LoggerFactory.getLogger(DailyScanService.class);

    public static final List<String> EXCLUDE_CATEGORIES = List.of("stablecoins", "wrapped-tokens", "bridged-tokens",
            "liquid-staking-tokens", "liquid-restaking-tokens", "exchange-based-tokens", "tokenized-gold");
    static final Set<String> MAJORS = Set.of("bitcoin", "ethereum");
    static final int NEW_POOL_CHECK = 20;

    public record Quality(int cgOk, int cgFailed, int cgRateLimited, int llamaOk, int llamaFailed,
                          int marketCoins, int universeTarget, int tracked, int trackedWithLatest,
                          int supplyKnown, int pathBMapped, String snapshotStatus, boolean exclusionsFresh,
                          List<String> problems) { }

    public record Outcome(LocalDate scanDay, String status, PathA.Benchmarks benchmarks, BenchmarkInfo benchInfo,
                          List<CandidateA> pathA, List<PathB.Result> pathB, List<NewCoin> newPool,
                          List<String> added, List<String> dropped, Quality quality, Path reportFile,
                          List<String> aGrades, Map<String, PrelimValuation.Result> prelim) { }

    public record BenchmarkInfo(int aTotal, int aComputable, int aNoCurrentData, int bTotal, int bComputable,
                                LocalDate baseDate, LocalDate midDate, Double breadthTop100Long) { }

    /** price = 最新完整日线收盘价；basePrice = 长期区间起点价（快照 2025-10-31，没有则一年前）；low30 = 近 30 天最低价。 */
    public record CandidateA(MarketCoin coin, PathA.Result result, boolean inPriorityRange,
                             Double price, Double basePrice, String baseLabel, Double low30) { }

    public record NewCoin(MarketCoin coin, LocalDate firstDay) { }

    private final PsProperties props;
    private final CoinGeckoClient cg;
    private final DefiLlamaClient llama;
    private final PriceStore prices;
    private final JdbcTemplate jdbc;
    private final SnapshotService snapshots;
    private final TelegramNotifier notifier;
    private final ReportWriter reports;
    private final Clock clock;
    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).findAndRegisterModules();

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Instant lastStart;
    private volatile Instant lastFinish;
    private volatile String lastStatus = "never";
    private volatile String lastError;

    public DailyScanService(PsProperties props, CoinGeckoClient cg, DefiLlamaClient llama, PriceStore prices,
                            JdbcTemplate jdbc, SnapshotService snapshots, TelegramNotifier notifier,
                            ReportWriter reports, Clock clock) {
        this.props = props;
        this.cg = cg;
        this.llama = llama;
        this.prices = prices;
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.notifier = notifier;
        this.reports = reports;
        this.clock = clock;
    }

    public boolean isRunning() {
        return running.get();
    }

    public Instant lastStart() {
        return lastStart;
    }

    public Instant lastFinish() {
        return lastFinish;
    }

    public String lastStatus() {
        return lastStatus;
    }

    public String lastError() {
        return lastError;
    }

    /** 扫描当天（UTC 日期，最新完整日线 = 当天 00:00 点）。同一时间只跑一个。 */
    public Outcome runToday() {
        return run(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

    public Outcome run(LocalDate scanDay) {
        if (!running.compareAndSet(false, true)) {
            log.info("扫描已在进行，跳过");
            return null;
        }
        lastStart = Instant.now(clock);
        lastError = null;
        jdbc.update("INSERT OR REPLACE INTO scan_run(scan_day, started_at, status) VALUES (?,?,?)",
                scanDay.toString(), lastStart.toString(), "running");
        try {
            Outcome o = doRun(scanDay);
            lastStatus = o.status();
            return o;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastStatus = "failed";
            lastError = "被中断（停机）";
            finishRun(scanDay, "failed", null, lastError);
            return null;
        } catch (Exception e) {
            log.error("扫描失败", e);
            lastStatus = "failed";
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            finishRun(scanDay, "failed", null, lastError);
            notifier.send("⚠️ PhoenixScout " + scanDay + " 扫描出错：" + lastError + "\n没有生成候选，明天会再跑；不会用旧数据冒充今天的结果。");
            return null;
        } finally {
            lastFinish = Instant.now(clock);
            running.set(false);
        }
    }

    private Outcome doRun(LocalDate day) throws Exception {
        cg.http().resetCounters();
        llama.http().resetCounters();
        List<String> problems = new ArrayList<>();
        PsProperties.Scan sc = props.scan();

        // 1. 快照
        SnapshotService.Status snap = snapshots.load();
        if (!snap.available()) problems.add(snap.message() + "：基准 A 与路径 A1 停止计算");

        // 2. 全市场行情（批量）
        List<MarketCoin> market = new ArrayList<>();
        int pages = (sc.universeTopN() + 249) / 250;
        for (int p = 1; p <= pages; p++) {
            try {
                market.addAll(cg.marketsPage(p, null));
            } catch (ApiException e) {
                problems.add("行情第 " + p + " 页获取失败（" + e.status() + "）");
            }
        }
        if (market.size() > sc.universeTopN()) market = new ArrayList<>(market.subList(0, sc.universeTopN()));
        if (market.isEmpty()) throw new IllegalStateException("全市场行情全部获取失败");
        prices.saveMarketSnapshot(day, market, Instant.now(clock));
        Map<String, MarketCoin> byId = new LinkedHashMap<>();
        market.forEach(c -> byId.putIfAbsent(c.id(), c));

        // 3. 排除名单（分类，7 天缓存；失败沿用上次结果）
        boolean exclFresh = true;
        Set<String> excluded = new HashSet<>();
        for (String cat : EXCLUDE_CATEGORIES) {
            try {
                Set<String> ids = cg.categoryIds(cat);
                excluded.addAll(ids);
                saveExcluded(cat, ids);
            } catch (ApiException e) {
                exclFresh = false;
                excluded.addAll(jdbc.queryForList("SELECT coin_id FROM excluded_coin WHERE category = ?", String.class, cat));
                problems.add("排除分类 " + cat + " 获取失败，沿用上次结果");
            }
        }
        if (snap.available()) {
            snap.snapshot().coins().values().forEach(c -> { if (!c.excludedCategories().isEmpty()) excluded.add(c.id()); });
        }
        List<MarketCoin> alts = market.stream().filter(c -> !excluded.contains(c.id()) && !MAJORS.contains(c.id())).toList();

        // 4. 跟踪集合
        Map<String, LocalDate> latestDays = prices.latestDays();
        // V2.3.1：基准取同市值区间（第 101–500 名的山寨币），每隔 4 个取 1 个，约 100 个成分，控制请求量
        List<String> histAlts = new ArrayList<>();
        if (snap.available()) {
            for (String id : snap.snapshot().historicalTopIds()) {
                var c = snap.snapshot().coins().get(id);
                if (MAJORS.contains(id) || excluded.contains(id) || (c != null && !c.excludedCategories().isEmpty())) continue;
                histAlts.add(id);
            }
        }
        List<String> benchA = bandSample(histAlts);
        List<String> breadthTop = histAlts.stream().limit(sc.benchmarkSize()).toList();
        List<String> benchB = bandSample(alts.stream().map(MarketCoin::id).toList());

        // 4b. 路径 B 协议（两次批量请求拿全部协议收入和代币映射）
        List<DefiLlamaClient.ProtocolRevenue> protos = new ArrayList<>();
        List<DefiLlamaClient.ProtocolRevenue> allProtos = new ArrayList<>();
        try {
            for (var p : llama.revenueOverview()) {
                allProtos.add(p);
                if (p.geckoId() == null || !byId.containsKey(p.geckoId()) || excluded.contains(p.geckoId())) continue;
                if (p.total30d() == null || p.total60dto30d() == null || p.total30d() < 30_000) continue;
                if (p.total30d() <= p.total60dto30d()) continue;
                protos.add(p);
            }
        } catch (ApiException e) {
            problems.add("DefiLlama 收入概览获取失败（" + e.status() + "），路径 B 今日未运行");
        }
        protos.sort(Comparator.comparing(DefiLlamaClient.ProtocolRevenue::total30d).reversed());
        Map<String, DefiLlamaClient.ProtocolRevenue> protoByCoin = new LinkedHashMap<>();
        for (var p : protos) {
            protoByCoin.putIfAbsent(p.geckoId(), p);   // 同一代币多个协议取收入最大的
            if (protoByCoin.size() >= 40) break;
        }

        // 4c. 路径 A 预筛：用本地历史 + 今日批量行情估算，只对最有希望的补精确日线
        Map<String, NavigableMap<LocalDate, DailyPoint>> stored = new HashMap<>(prices.histories(
                alts.stream().map(MarketCoin::id).toList()));
        stored.putAll(prices.histories(MAJORS));
        Double btcEst30 = estReturn(stored.get("bitcoin"), byId.get("bitcoin"), day, 30);
        Double ethEst30 = estReturn(stored.get("ethereum"), byId.get("ethereum"), day, 30);
        double bar30 = Math.max(nz(btcEst30), nz(ethEst30)) - 0.05;
        record Pre(MarketCoin c, double est30) { }
        List<Pre> pre = new ArrayList<>();
        List<MarketCoin> newcomers = new ArrayList<>();
        for (MarketCoin c : alts) {
            if (c.marketCap() == null || c.marketCap() < sc.capMinUsd() / 3 || c.marketCap() > sc.capMaxUsd() * 3) continue;
            NavigableMap<LocalDate, DailyPoint> h = stored.get(c.id());
            if (h == null || h.isEmpty() || h.firstKey().isAfter(day.minusDays(90))) {
                if (c.marketCap() >= sc.capMinUsd()) newcomers.add(c);
                continue;
            }
            Double e30 = estReturn(h, c, day, 30);
            if (e30 == null || e30 <= bar30) continue;
            if (!estLongWeak(h, c, day, snap, stored)) continue;
            pre.add(new Pre(c, e30));
        }
        pre.sort(Comparator.comparingDouble(Pre::est30).reversed());
        List<String> preIds = pre.stream().limit(sc.maxCandidateFetch()).map(p -> p.c().id()).toList();
        newcomers.sort(Comparator.comparing(MarketCoin::marketCap).reversed());
        List<String> newIds = newcomers.stream().limit(NEW_POOL_CHECK).map(MarketCoin::id).toList();

        LinkedHashSet<String> tracked = new LinkedHashSet<>(List.of("bitcoin", "ethereum"));
        tracked.addAll(benchA);
        tracked.addAll(benchB);
        tracked.addAll(preIds);
        tracked.addAll(protoByCoin.keySet());
        tracked.addAll(newIds);

        // 5. 增量补齐精确日线（只补缺的天数）
        for (String id : tracked) {
            LocalDate last = latestDays.get(id);
            if (last != null && !last.isBefore(day)) continue;
            int days = last == null ? 365 : (int) Math.min(365, ChronoUnit.DAYS.between(last, day) + 1);
            try {
                TreeMap<LocalDate, DailyPoint> pts = cg.marketChart(id, Math.max(days, 2));
                prices.saveDaily(id, pts.values(), "coingecko-market_chart", Instant.now(clock));
            } catch (ApiException e) {
                if (e.status() != 404) log.debug("日线 {} 获取失败：{}", id, e.getMessage());
            }
        }
        Map<String, TreeMap<LocalDate, DailyPoint>> hist = prices.histories(tracked);
        int withLatest = (int) tracked.stream().filter(id -> Returns.price(hist.get(id), day).isPresent()).count();
        if (Returns.price(hist.get("bitcoin"), day).isEmpty() || Returns.price(hist.get("ethereum"), day).isEmpty()) {
            problems.add("BTC/ETH 今日 00:00 日线缺失（可能尚未生成），所有相对比较未判定");
        }

        // 6. 基准
        Double breadthTopLong = null;
        PathA.Benchmarks bm;
        BenchmarkInfo bi;
        {
            var s = snap.available() ? snap.snapshot() : null;
            Double btcBase = s == null ? null : snapPrice(s, "bitcoin", s.baseDate());
            Double btcMid = s == null ? null : snapPrice(s, "bitcoin", s.midDate());
            Double ethBase = s == null ? null : snapPrice(s, "ethereum", s.baseDate());
            Double ethMid = s == null ? null : snapPrice(s, "ethereum", s.midDate());
            Double btcNow = Returns.price(hist.get("bitcoin"), day).orElse(null);
            Double ethNow = Returns.price(hist.get("ethereum"), day).orElse(null);
            List<Double> aLong = new ArrayList<>(), aRecent = new ArrayList<>();
            int aNoData = 0;
            for (String id : benchA) {
                Double b0 = snapPrice(s, id, s.baseDate());
                Double b1 = snapPrice(s, id, s.midDate());
                Returns.ret(b0, b1).ifPresent(aLong::add);
                Double now = Returns.price(hist.get(id), day).orElse(null);
                if (now == null) aNoData++;
                Returns.ret(b1, now).ifPresent(aRecent::add);
            }
            List<Double> b30 = new ArrayList<>(), b90 = new ArrayList<>();
            for (String id : benchB) {
                Returns.rolling(hist.get(id), day, 30).ifPresent(b30::add);
                Returns.rolling(hist.get(id), day, 90).ifPresent(b90::add);
            }
            // 成分缺失过多（可计算不足一半）就不给基准，避免用少数幸存样本冒充完整中位数
            Double medALong = aLong.size() * 2 >= benchA.size() && !benchA.isEmpty() ? Returns.median(aLong).orElse(null) : null;
            List<Double> topLong = new ArrayList<>();
            for (String id : breadthTop) Returns.ret(snapPrice(s, id, s.baseDate()), snapPrice(s, id, s.midDate())).ifPresent(topLong::add);
            breadthTopLong = Returns.median(topLong).orElse(null);
            Double medARecent = aRecent.size() * 2 >= benchA.size() && !benchA.isEmpty() ? Returns.median(aRecent).orElse(null) : null;
            Double medB30 = b30.size() * 2 >= benchB.size() && !benchB.isEmpty() ? Returns.median(b30).orElse(null) : null;
            Double medB90 = b90.size() * 2 >= benchB.size() && !benchB.isEmpty() ? Returns.median(b90).orElse(null) : null;
            var bh = hist.get("bitcoin");
            var eh = hist.get("ethereum");
            bm = new PathA.Benchmarks(Returns.ret(btcBase, btcMid).orElse(null), Returns.ret(ethBase, ethMid).orElse(null), medALong,
                    Returns.ret(btcMid, btcNow).orElse(null), Returns.ret(ethMid, ethNow).orElse(null), medARecent,
                    Returns.rolling(bh, day, 30).orElse(null), Returns.rolling(eh, day, 30).orElse(null), medB30,
                    Returns.rolling(bh, day, 90).orElse(null), Returns.rolling(eh, day, 90).orElse(null), medB90,
                    Returns.rolling(bh, day, 180).orElse(null), Returns.rolling(eh, day, 180).orElse(null),
                    Returns.rolling(bh, day, 365).orElse(null), Returns.rolling(eh, day, 365).orElse(null));
            bi = new BenchmarkInfo(benchA.size(), Math.min(aLong.size(), aRecent.size()), aNoData, benchB.size(),
                    Math.min(b30.size(), b90.size()), s == null ? null : s.baseDate(), s == null ? null : s.midDate(), breadthTopLong);
        }

        // 7. 路径 A
        List<CandidateA> pathA = new ArrayList<>();
        for (String id : preIds) {
            var s = snap.available() ? snap.snapshot() : null;
            Double base = s == null ? null : snapPrice(s, id, s.baseDate());
            Double mid = s == null ? null : snapPrice(s, id, s.midDate());
            PathA.Result r = PathA.evaluate(id, hist.getOrDefault(id, new TreeMap<>()), day, base, mid, bm);
            if (r.candidate()) {
                MarketCoin c = byId.get(id);
                boolean inRange = c.marketCap() != null && c.marketCap() >= sc.capMinUsd() && c.marketCap() <= sc.capMaxUsd();
                var hh = hist.get(id);
                Double basePx = base;
                String baseLabel = "2025-10-31 价";
                if (basePx == null) {
                    basePx = Returns.price(hh, day.minusDays(365)).orElse(null);
                    baseLabel = "一年前价";
                }
                pathA.add(new CandidateA(c, r, inRange, Returns.price(hh, day).orElse(null), basePx, baseLabel,
                        Returns.minPrice(hh, day.minusDays(29), day).orElse(null)));
            }
        }
        pathA.sort(Comparator.comparing((CandidateA c) -> c.result().a1Pass()).reversed()
                .thenComparing(c -> -nz(c.result().r90())));

        // 8. 路径 B
        List<PathB.Result> pathB = new ArrayList<>();
        for (var e : protoByCoin.entrySet()) {
            try {
                var rev = llama.dailyRevenue(e.getValue().slug());
                PathB.Result r = PathB.evaluate(e.getKey(), e.getValue().name(), rev, day.minusDays(1),
                        Returns.rolling(hist.get(e.getKey()), day, 90).orElse(null));
                // 净值型代币（基金份额等，90 天价格波动不到 5%）不是我们要找的标的
                var lo = Returns.minPrice(hist.get(e.getKey()), day.minusDays(90), day);
                var hi = maxPrice(hist.get(e.getKey()), day.minusDays(90), day);
                boolean navLike = lo.isPresent() && hi != null && hi / lo.get() < 1.05;
                if (r.pass() && !navLike) pathB.add(r);
            } catch (ApiException ex) {
                problems.add("协议 " + e.getValue().name() + " 收入明细获取失败");
            }
        }
        pathB.sort(Comparator.comparing(PathB.Result::priceLagging).reversed()
                .thenComparing(r -> -nz(r.revenueGrowth())));

        // 8b. 路径 B 初步情景推算：同类协议“流通市值/年化收入”倍数 + 最近季度收入（研究层核实前的参考）
        Map<String, PrelimValuation.Result> prelim = new LinkedHashMap<>();
        {
            Map<String, double[]> revByCoin = new HashMap<>();      // [年化收入, 最大单协议收入]
            Map<String, String> catByCoin = new HashMap<>();
            for (var p : allProtos) {
                if (p.geckoId() == null || p.total30d() == null || p.total30d() <= 0 || excluded.contains(p.geckoId())) continue;
                double annual = p.total30d() * 365.0 / 30.0;
                double[] v = revByCoin.computeIfAbsent(p.geckoId(), k -> new double[2]);
                v[0] += annual;
                if (annual > v[1]) {
                    v[1] = annual;
                    catByCoin.put(p.geckoId(), p.category());
                }
            }
            List<PrelimValuation.Peer> peers = new ArrayList<>();
            revByCoin.forEach((id, v) -> {
                MarketCoin c = byId.get(id);
                if (c != null && c.marketCap() != null && c.marketCap() > 0) peers.add(new PrelimValuation.Peer(catByCoin.get(id), c.marketCap(), v[0]));
            });
            for (PathB.Result r : pathB) {
                MarketCoin c = byId.get(r.coinId());
                if (c == null || c.price() == null || c.circulating() == null || c.marketCap() == null) continue;
                var pv = PrelimValuation.compute(r.coinId(), catByCoin.get(r.coinId()), r, c.price(), c.circulating(), c.marketCap(),
                        peers);
                if (pv != null) prelim.put(r.coinId(), pv);
            }
        }

        // 9. 新币观察池（历史不足 90 天，不参与路径 A 排名，不伪造历史）
        List<NewCoin> newPool = new ArrayList<>();
        for (String id : newIds) {
            var h = hist.get(id);
            LocalDate first = h == null || h.isEmpty() ? null : h.firstKey();
            if (first == null || first.isAfter(day.minusDays(90))) newPool.add(new NewCoin(byId.get(id), first));
        }

        // 10. 保存状态并与上次比较（研究层上线前，所有候选上限 B 级）
        List<String> added = new ArrayList<>(), dropped = new ArrayList<>();
        saveCandidates(day, pathA, pathB, newPool);
        compareWithPrevious(day, added, dropped);

        int supplyKnown = (int) market.stream().filter(c -> c.circulating() != null && c.totalSupply() != null).count();
        Quality q = new Quality(cg.http().okCount(), cg.http().failedCount(), cg.http().rateLimitedCount(),
                llama.http().okCount(), llama.http().failedCount(), market.size(), sc.universeTopN(), tracked.size(),
                withLatest, supplyKnown, protoByCoin.size(), snap.message(), exclFresh, problems);
        String status = problems.isEmpty() ? "ok" : "partial";
        List<String> aGrades = currentAGrades(byId);
        Outcome o = new Outcome(day, status, bm, bi, pathA, pathB, newPool, added, dropped, q, null, aGrades, prelim);
        Path file = reports.write(o);
        o = new Outcome(day, status, bm, bi, pathA, pathB, newPool, added, dropped, q, file, aGrades, prelim);
        finishRun(day, status, om.writeValueAsString(q), ReportWriter.oneLine(o));
        boolean sent = notifier.send(reports.telegramSummary(o));
        jdbc.update("INSERT INTO notification(kind, scan_day, sent_at, ok) VALUES ('daily', ?, ?, ?)",
                day.toString(), Instant.now(clock).toString(), sent ? 1 : 0);
        log.info("扫描完成 {}：路径A {} 个，路径B {} 个，新币 {} 个，状态 {}", day, pathA.size(), pathB.size(), newPool.size(), status);
        return o;
    }

    /** 研究层评为 A 且 14 天内仍有效的币（每个币取最新一次评级）。 */
    private List<String> currentAGrades(Map<String, MarketCoin> byId) {
        String since = Instant.now(clock).minus(14, ChronoUnit.DAYS).toString();
        List<String> ids = jdbc.queryForList("SELECT g.coin_id FROM research_grade g JOIN (SELECT coin_id, MAX(id) mid FROM research_grade"
                + " GROUP BY coin_id) l ON g.id = l.mid WHERE g.grade = 'A' AND g.graded_at >= ? ORDER BY g.id DESC", String.class, since);
        return ids.stream().map(id -> byId.containsKey(id) ? byId.get(id).name() + "（" + byId.get(id).symbol().toUpperCase(java.util.Locale.ROOT) + "）" : id).toList();
    }

    private void finishRun(LocalDate day, String status, String quality, String summary) {
        jdbc.update("UPDATE scan_run SET finished_at = ?, status = ?, quality_json = ?, summary = ? WHERE scan_day = ?",
                Instant.now(clock).toString(), status, quality, summary, day.toString());
    }

    private void saveExcluded(String cat, Set<String> ids) {
        String now = Instant.now(clock).toString();
        jdbc.update("DELETE FROM excluded_coin WHERE category = ?", cat);
        jdbc.batchUpdate("INSERT OR REPLACE INTO excluded_coin(coin_id, category, updated_at) VALUES (?,?,?)",
                ids.stream().map(id -> new Object[]{id, cat, now}).toList());
    }

    private void saveCandidates(LocalDate day, List<CandidateA> a, List<PathB.Result> b, List<NewCoin> n) throws Exception {
        jdbc.update("DELETE FROM candidate_state WHERE scan_day = ?", day.toString());
        List<Object[]> rows = new ArrayList<>();
        for (CandidateA c : a) rows.add(new Object[]{day.toString(), c.coin().id(), "A", "B", om.writeValueAsString(c.result())});
        for (PathB.Result r : b) rows.add(new Object[]{day.toString(), r.coinId(), "B", "B", om.writeValueAsString(r)});
        for (NewCoin c : n) rows.add(new Object[]{day.toString(), c.coin().id(), "NEW", "-", "{}"});
        jdbc.batchUpdate("INSERT OR REPLACE INTO candidate_state(scan_day, coin_id, path, grade, detail) VALUES (?,?,?,?,?)", rows);
    }

    private void compareWithPrevious(LocalDate day, List<String> added, List<String> dropped) {
        var prev = prices.previousScanDay(day);
        Set<String> now = new HashSet<>(jdbc.queryForList(
                "SELECT coin_id || ':' || path FROM candidate_state WHERE scan_day = ? AND path IN ('A','B')", String.class, day.toString()));
        if (prev.isEmpty()) {
            added.addAll(now);
            return;
        }
        Set<String> before = new HashSet<>(jdbc.queryForList(
                "SELECT coin_id || ':' || path FROM candidate_state WHERE scan_day = ? AND path IN ('A','B')", String.class, prev.get().toString()));
        now.stream().filter(x -> !before.contains(x)).sorted().forEach(added::add);
        before.stream().filter(x -> !now.contains(x)).sorted().forEach(dropped::add);
    }

    static Double maxPrice(NavigableMap<LocalDate, DailyPoint> h, LocalDate from, LocalDate to) {
        if (h == null) return null;
        Double m = null;
        for (var p : h.subMap(from, true, to, true).values()) {
            if (p.price() != null) m = m == null ? p.price() : Math.max(m, p.price());
        }
        return m;
    }

    /** 第 101–500 名（下标 100–499）每隔 4 个取 1 个。 */
    static List<String> bandSample(List<String> ranked) {
        List<String> out = new ArrayList<>();
        for (int i = 100; i < Math.min(500, ranked.size()); i += 4) out.add(ranked.get(i));
        return out;
    }

    static Double snapPrice(SnapshotService.Snapshot s, String id, LocalDate d) {
        if (s == null) return null;
        var c = s.coins().get(id);
        if (c == null) return null;
        var p = c.points().get(d);
        return p == null || !"ok".equals(p.priceFlag()) ? null : p.price();
    }

    /** 预筛用估算：今日批量行情价（非收盘价）对比本地历史，仅用于决定是否补精确日线，不进入任何判定。 */
    static Double estReturn(NavigableMap<LocalDate, DailyPoint> h, MarketCoin now, LocalDate day, int days) {
        if (h == null || now == null || now.price() == null) return null;
        var past = Returns.price(h, day.minusDays(days));
        return past.isPresent() ? now.price() / past.get() - 1 : null;
    }

    private boolean estLongWeak(NavigableMap<LocalDate, DailyPoint> h, MarketCoin c, LocalDate day,
                                SnapshotService.Status snap, Map<String, NavigableMap<LocalDate, DailyPoint>> stored) {
        if (snap.available()) {
            var s = snap.snapshot();
            Double r = Returns.ret(snapPrice(s, c.id(), s.baseDate()), snapPrice(s, c.id(), s.midDate())).orElse(null);
            Double rb = Returns.ret(snapPrice(s, "bitcoin", s.baseDate()), snapPrice(s, "bitcoin", s.midDate())).orElse(null);
            Double re = Returns.ret(snapPrice(s, "ethereum", s.baseDate()), snapPrice(s, "ethereum", s.midDate())).orElse(null);
            if (r != null && rb != null && re != null) return r < Math.min(rb, re);
        }
        Double r = estReturn(h, c, day, 365);
        // 没有快照点时退回 365 天：用历史里一年前的价格对比 BTC/ETH 一年收益（预筛宽松，正式判定在 PathA）
        var bh = stored.get("bitcoin");
        var eh = stored.get("ethereum");
        Double b365 = bh == null ? null : Returns.ret(bh, day.minusDays(365), bh.lastKey()).orElse(null);
        Double e365 = eh == null ? null : Returns.ret(eh, day.minusDays(365), eh.lastKey()).orElse(null);
        return r != null && b365 != null && e365 != null && r < Math.min(b365, e365);
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }
}
