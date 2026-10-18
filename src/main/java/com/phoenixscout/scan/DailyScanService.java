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
                          List<String> aGrades, Map<String, PrelimValuation.Result> prelim,
                          List<Pick> picks, List<com.phoenixscout.sim.SimLedger.Event> simEvents,
                          com.phoenixscout.sim.SimLedger.Summary simSummary,
                          List<Rec> aList, List<Rec> bList, List<Eliminated> eliminated, int excludedByQuality,
                          int pathATotalBeforeCap, Map<String, Object> backtest) { }

    /** V2.6 推荐：A 级最多 3 个，B 级观察最多 5 个；路径 A 入选超过 30 个时按相对强度只保留前 20。 */
    public static final int MAX_A_GRADE = 3;
    public static final int MAX_B_WATCH = 5;
    public static final int A_CROWDED = 30;
    public static final int MAX_A_KEEP_WHEN_CROWDED = 20;

    /** 一个候选的完整评估（日报每个币显示的内容）。 */
    public record Rec(String coinId, String name, String path, String grade, double price, Double marketCap, Double fdv,
                      Double r30, Double r90, Double excess30, Double excess90, Double strength,
                      com.phoenixscout.research.QualityScore.Result quality, com.phoenixscout.research.UnlockRisk.Result unlock,
                      Valuation.Target target, StrategyBacktest.Ev ev, double stop, double downside, Double refPrice,
                      List<String> risks, String reason, boolean excluded) { }

    public record Eliminated(String coinId, String name, String grade, String reason) { }

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
    private com.phoenixscout.sim.SimLedger sim;
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

    public void setSimLedger(com.phoenixscout.sim.SimLedger sim) {
        this.sim = sim;
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
            notifier.send("⚠️ " + scanDay + " 扫描出错：" + lastError + "\n没有生成候选，明天会再跑；不会用旧数据冒充今天的结果。");
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
        if (sim != null) tracked.addAll(sim.coinsNeedingHistory(day));   // 模拟台账：组合持仓每天、选币台账每 7 天、到期观察点

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
        Map<String, PathA.Result> pathAAll = new HashMap<>();
        for (String id : preIds) {
            var s = snap.available() ? snap.snapshot() : null;
            Double base = s == null ? null : snapPrice(s, id, s.baseDate());
            Double mid = s == null ? null : snapPrice(s, id, s.midDate());
            PathA.Result r = PathA.evaluate(id, hist.getOrDefault(id, new TreeMap<>()), day, base, mid, bm);
            pathAAll.put(id, r);
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
        // V2.6：按相对强度（30 天 + 90 天超额）排序；超过 30 个只保留前 20
        pathA.sort(Comparator.comparing((CandidateA c) -> -nz(c.result().strength())));
        int pathATotal = pathA.size();
        if (pathA.size() > A_CROWDED) pathA = new ArrayList<>(pathA.subList(0, MAX_A_KEEP_WHEN_CROWDED));

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
        Map<String, double[]> revByCoin = new HashMap<>();      // [年化收入, 最大单协议收入]
        Map<String, String> catByCoin = new HashMap<>();
        List<Valuation.Peer> valPeers = new ArrayList<>();
        {
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
                if (c != null && c.marketCap() != null && c.marketCap() > 0) {
                    peers.add(new PrelimValuation.Peer(catByCoin.get(id), c.marketCap(), v[0]));
                    valPeers.add(new Valuation.Peer(catByCoin.get(id), c.marketCap(), v[0]));
                }
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

        // 9b. V2.6 推荐：质量分、解锁、估值目标、期望值 → A 级（最多 3）/ B 级观察（最多 5）
        Map<String, Object> btInfo = new LinkedHashMap<>();
        BtData bt = backtestData(day, excluded, benchA, btInfo, problems);
        Map<String, Double> holders = Map.of();
        Set<String> audited = Set.of();
        try {
            holders = llama.holdersRevenue30dByCoin();
            audited = llama.auditedCoins();
        } catch (ApiException e) {
            problems.add("DefiLlama 持币人收入/审计数据获取失败，质量分第 3、5 项只看研究层");
        }
        Set<String> candIds = new LinkedHashSet<>();
        pathA.forEach(c -> candIds.add(c.coin().id()));
        pathB.forEach(r -> candIds.add(r.coinId()));
        Map<String, com.fasterxml.jackson.databind.JsonNode> raw =
                com.phoenixscout.research.ResearchService.latestRaw(jdbc, candIds, Instant.now(clock));
        Set<String> researchA = new HashSet<>(jdbc.queryForList("SELECT g.coin_id FROM research_grade g JOIN (SELECT coin_id, MAX(id) mid"
                + " FROM research_grade GROUP BY coin_id) l ON g.id = l.mid WHERE g.grade = 'A' AND g.graded_at >= ?", String.class,
                Instant.now(clock).minus(14, ChronoUnit.DAYS).toString()));
        Map<String, Valuation.PeerStats> peerCache = new HashMap<>();
        int exchangeFailed = 0;
        List<Rec> recs = new ArrayList<>();
        Map<String, String> whyNot = new HashMap<>();
        Map<String, Object[]> sources = new LinkedHashMap<>();   // coin -> [path, CandidateA|PathB.Result]
        for (CandidateA c : pathA) sources.put(c.coin().id(), new Object[]{"A", c});
        for (PathB.Result r : pathB) sources.putIfAbsent(r.coinId(), new Object[]{"B", r});
        for (var e : sources.entrySet()) {
            String id = e.getKey();
            String path = (String) e.getValue()[0];
            MarketCoin mc = byId.get(id);
            var h = hist.get(id);
            Double px = Returns.price(h, day).orElse(null);
            if (mc == null || px == null) continue;
            Double r30, r90, ex30, ex90, strength, ref = null;
            String reason;
            if (path.equals("A")) {
                CandidateA c = (CandidateA) e.getValue()[1];
                var r = c.result();
                r30 = r.r30(); r90 = r.r90(); ex30 = r.excess30(); ex90 = r.excess90(); strength = r.strength();
                ref = c.basePrice();
                reason = String.format(java.util.Locale.ROOT, "超跌转强：长期 %s；30天 %s、90天 %s，比最强基准高 %s/%s 个百分点；量能 %.1f 倍，站上 90 日均线",
                        ReportWriter.pct(r.rLong()), ReportWriter.pct(r.r30()), ReportWriter.pct(r.r90()), pp(ex30), pp(ex90),
                        r.volumeRatio() == null ? 0 : r.volumeRatio());
            } else {
                PathB.Result b = (PathB.Result) e.getValue()[1];
                r30 = Returns.rolling(h, day, 30).orElse(null);
                r90 = Returns.rolling(h, day, 90).orElse(null);
                Double best30 = PathA.maxAll(bm.btc30(), bm.eth30(), bm.benchB30()), best90 = PathA.maxAll(bm.btc90(), bm.eth90(), bm.benchB90());
                ex30 = r30 == null || best30 == null ? null : r30 - best30;
                ex90 = r90 == null || best90 == null ? null : r90 - best90;
                strength = ex30 == null || ex90 == null ? null : ex30 + ex90;
                reason = String.format(java.util.Locale.ROOT, "收入连增：%s 季增 %s，币价 90 天 %s%s", b.protocol(),
                        ReportWriter.pct(b.revenueGrowth()), ReportWriter.pct(b.price90()), b.priceLagging() ? "，价格未反映" : "");
            }
            List<String> exchanges;
            try {
                exchanges = cg.majorSpotExchanges(id);
            } catch (ApiException ex) {
                exchanges = null;
                exchangeFailed++;
            }
            double[] rev = revByCoin.get(id);
            var quality = com.phoenixscout.research.QualityScore.evaluate(new com.phoenixscout.research.QualityScore.Auto(
                    rev != null && rev[0] > 0, holders.getOrDefault(id, 0.0) > 0, exchanges, audited.contains(id)), raw.get(id));
            var unlock = com.phoenixscout.research.UnlockRisk.evaluate(mc.circulating(), mc.totalSupply(), raw.get(id));
            String cat = catByCoin.get(id);
            Valuation.PeerStats ps = peerCache.computeIfAbsent(cat == null ? "" : cat, k -> Valuation.peers(cat, valPeers));
            var target = Valuation.target(px, rev == null ? null : rev[0], cat, ps, mc.circulating(), unlock.supply12m(),
                    mc.totalSupply(), mc.maxSupply());
            Double low90 = Returns.minPrice(h, day.minusDays(89), day).orElse(null);
            double stop = Pick.stopFor(px, low90), down = 1 - stop / px;
            StrategyBacktest.Ev ev;
            if (path.equals("B")) ev = new StrategyBacktest.Ev(null, null, null, null, "样本不足（路径 B 没有可回测的历史收入数据）");
            else if (target.available() && target.upside() <= 0) ev = new StrategyBacktest.Ev(null, null, null, null, "现价已高于估值目标");
            else ev = StrategyBacktest.expectedValue(bt.episodes(), bt.hist(), day, target.available() ? target.upside() : null, down);
            List<String> risks = new ArrayList<>(unlock.flags());
            if (!target.available()) risks.add(target.reason());
            else if (target.upside() <= 0) risks.add("现价已高于估值目标");
            if (quality.selfReported()) risks.add("市值为项目自报");
            Returns.avgVolume(h, day.minusDays(29), day).ifPresent(v -> { if (v < 5e6) risks.add("流动性偏低：30 天日均成交额 " + ReportWriter.usd(v)); });
            if (path.equals("A")) {
                var r = ((CandidateA) e.getValue()[1]).result();
                if (r.rLong() != null && r.rLong() < -0.90) risks.add("长期跌幅超过 90%，归零风险高");
            }
            boolean gradeA = researchA.contains(id) && "A".equals(quality.cap()) && !unlock.blocksA() && target.available()
                    && target.upside() > 0;
            String grade = gradeA ? "A" : "B";
            if (quality.excluded()) whyNot.put(id, "质量分 " + quality.score() + "/5（已研究，≤1 分排除）");
            String name = mc.name() + "（" + mc.symbol().toUpperCase(java.util.Locale.ROOT) + "）";
            recs.add(new Rec(id, name, path, grade, px, mc.marketCap(), mc.fdv(), r30, r90, ex30, ex90, strength, quality, unlock,
                    target, ev, stop, down, ref, risks, reason, quality.excluded()));
        }
        if (exchangeFailed > 0) problems.add("CoinGecko 交易所数据 " + exchangeFailed + " 个获取失败，质量分第 4 项按“无”计");
        Comparator<Rec> byEv = Comparator.comparing((Rec r) -> r.ev() == null || r.ev().ev() == null ? Double.NEGATIVE_INFINITY : r.ev().ev())
                .reversed().thenComparing(r -> -nz(r.strength()));
        List<Rec> aList = recs.stream().filter(r -> r.grade().equals("A") && !r.excluded()).sorted(byEv).limit(MAX_A_GRADE).toList();
        List<Rec> bList = recs.stream().filter(r -> r.grade().equals("B") && !r.excluded()).sorted(byEv).limit(MAX_B_WATCH).toList();
        int excludedCnt = (int) recs.stream().filter(Rec::excluded).count();
        Set<String> listed = new HashSet<>();
        aList.forEach(r -> listed.add(r.coinId()));
        bList.forEach(r -> listed.add(r.coinId()));
        for (Rec r : recs) if (!listed.contains(r.coinId()) && !r.excluded()) whyNot.put(r.coinId(), "排名靠后（期望值/相对强度不及今日入选的币）");
        List<Eliminated> eliminated = eliminated(day, listed, whyNot, pathAAll);
        saveRecs(day, aList, bList);

        List<Pick> picks = new ArrayList<>();
        for (Rec r : java.util.stream.Stream.concat(aList.stream(), bList.stream()).toList()) {
            picks.add(new Pick(r.coinId(), r.name(), r.path(), r.grade(), r.quality().score(), r.quality().researched(), r.price(),
                    r.target().available() && r.target().upside() > 0 ? r.target().targetPrice() : null, null, r.stop(),
                    r.ev() == null ? null : r.ev().ev(), r.target().available() ? r.target().upside() : null, r.downside(), r.reason()));
        }
        List<com.phoenixscout.sim.SimLedger.Event> simEvents = List.of();
        com.phoenixscout.sim.SimLedger.Summary simSummary = null;
        if (sim != null) {
            java.util.Set<String> invalid = new java.util.HashSet<>(jdbc.queryForList(
                    "SELECT g.coin_id FROM research_grade g JOIN (SELECT coin_id, MAX(id) mid FROM research_grade GROUP BY coin_id) l"
                            + " ON g.id = l.mid WHERE g.grade = 'C'", String.class));
            simEvents = sim.run(day, hist, picks, invalid, benchB);
            simSummary = sim.summary(com.phoenixscout.sim.SimLedger.PORTFOLIO);
            try {
                sim.exportCsv(reports.dir().getParent().resolve("sim").resolve("ledger.csv"));
            } catch (Exception e) {
                problems.add("模拟台账 CSV 导出失败：" + e.getMessage());
            }
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
        Outcome o = new Outcome(day, status, bm, bi, pathA, pathB, newPool, added, dropped, q, null, aGrades, prelim,
                picks, simEvents, simSummary, aList, bList, eliminated, excludedCnt, pathATotal, btInfo);
        Path file = reports.write(o);
        o = new Outcome(day, status, bm, bi, pathA, pathB, newPool, added, dropped, q, file, aGrades, prelim,
                picks, simEvents, simSummary, aList, bList, eliminated, excludedCnt, pathATotal, btInfo);
        finishRun(day, status, om.writeValueAsString(q), ReportWriter.oneLine(o));
        boolean sent = notifier.send(reports.telegramSummary(o));
        // 每周日（越南时间）推送模拟台账复盘
        if (sim != null && day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            notifier.send(new com.phoenixscout.sim.SimReview(jdbc).weeklyText(sim));
        }
        jdbc.update("INSERT INTO notification(kind, scan_day, sent_at, ok) VALUES ('daily', ?, ?, ?)",
                day.toString(), Instant.now(clock).toString(), sent ? 1 : 0);
        log.info("扫描完成 {}：路径A {} 个，路径B {} 个，A级 {} 个，B级观察 {} 个，状态 {}", day, pathATotal, pathB.size(), aList.size(), bList.size(), status);
        return o;
    }

    static String pp(Double v) {
        return v == null ? "缺失" : String.format(java.util.Locale.ROOT, "%+.0f", v * 100);
    }

    /** 每周回测结果与期望值样本。 */
    record BtData(List<StrategyBacktest.Signal> episodes, Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist) { }

    /**
     * 每周日（或 7 天内没有回测结果时）用本地全部日线重跑一次路径 A 策略回测，保存样本；平时读取最近一次的样本。
     * 期望值的概率只来自这些样本。
     */
    BtData backtestData(LocalDate day, Set<String> excluded, List<String> benchA, Map<String, Object> info, List<String> problems) {
        try {
            List<Map<String, Object>> last = jdbc.queryForList("SELECT run_day, json FROM bt_run ORDER BY run_day DESC LIMIT 1");
            boolean stale = last.isEmpty() || LocalDate.parse((String) last.get(0).get("run_day")).isBefore(day.minusDays(6))
                    || (day.getDayOfWeek() == java.time.DayOfWeek.SUNDAY && !day.toString().equals(last.get(0).get("run_day")));
            if (stale) {
                List<String> all = jdbc.queryForList("SELECT DISTINCT coin_id FROM price_daily", String.class);
                Map<String, TreeMap<LocalDate, DailyPoint>> h = prices.histories(all);
                LocalDate first = h.values().stream().filter(m -> !m.isEmpty()).map(TreeMap::firstKey).min(LocalDate::compareTo).orElse(day);
                LocalDate from = first.plusDays(120);
                var sig = StrategyBacktest.signals(h, excluded, benchA, from, day);
                var rep = StrategyBacktest.report(sig, h, from, day, day);
                Map<String, Object> m = StrategyBacktest.toMap(rep);
                jdbc.update("INSERT OR REPLACE INTO bt_run(run_day, finished_at, json) VALUES (?,?,?)", day.toString(),
                        Instant.now(clock).toString(), om.writeValueAsString(m));
                jdbc.update("DELETE FROM bt_episode WHERE run_day <> ?", day.toString());
                jdbc.batchUpdate("INSERT OR REPLACE INTO bt_episode(run_day, coin_id, day, entry) VALUES (?,?,?,?)",
                        sig.episodes().stream().map(e -> new Object[]{day.toString(), e.coinId(), e.day().toString(), e.price()}).toList());
                info.putAll(m);
                info.put("runDay", day.toString());
                Map<String, NavigableMap<LocalDate, DailyPoint>> keep = new HashMap<>();
                for (var e : sig.episodes()) keep.put(e.coinId(), h.get(e.coinId()));
                return new BtData(sig.episodes(), keep);
            }
            String runDay = (String) last.get(0).get("run_day");
            info.putAll(om.readValue((String) last.get(0).get("json"), Map.class));
            info.put("runDay", runDay);
            List<StrategyBacktest.Signal> eps = new ArrayList<>();
            for (var r : jdbc.queryForList("SELECT coin_id, day, entry FROM bt_episode WHERE run_day = ?", runDay)) {
                eps.add(new StrategyBacktest.Signal((String) r.get("coin_id"), LocalDate.parse((String) r.get("day")),
                        ((Number) r.get("entry")).doubleValue(), 0));
            }
            Set<String> ids = new HashSet<>();
            eps.forEach(e -> ids.add(e.coinId()));
            return new BtData(eps, prices.histories(ids));
        } catch (Exception e) {
            log.warn("策略回测失败", e);
            problems.add("策略回测失败：" + e.getMessage() + "；期望值今日不输出");
            info.put("verdict", "回测失败，策略尚未验证有效");
            info.put("validated", false);
            return new BtData(List.of(), Map.of());
        }
    }

    private void saveRecs(LocalDate day, List<Rec> a, List<Rec> b) {
        jdbc.update("DELETE FROM rec_daily WHERE scan_day = ?", day.toString());
        List<Object[]> rows = new ArrayList<>();
        int i = 0;
        for (Rec r : a) rows.add(new Object[]{day.toString(), r.coinId(), r.name(), "A", ++i});
        for (Rec r : b) rows.add(new Object[]{day.toString(), r.coinId(), r.name(), "B", ++i});
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO rec_daily(scan_day, coin_id, name, grade, rank) VALUES (?,?,?,?,?)", rows);
    }

    /** 上一次推荐（A + B 观察）里今天不在名单上的币和原因。 */
    private List<Eliminated> eliminated(LocalDate day, Set<String> listed, Map<String, String> whyNot, Map<String, PathA.Result> pathAAll) {
        List<String> prev = jdbc.queryForList("SELECT MAX(scan_day) FROM rec_daily WHERE scan_day < ?", String.class, day.toString());
        if (prev.isEmpty() || prev.get(0) == null) return List.of();
        List<Eliminated> out = new ArrayList<>();
        for (var r : jdbc.queryForList("SELECT coin_id, name, grade FROM rec_daily WHERE scan_day = ? ORDER BY rank", prev.get(0))) {
            String id = (String) r.get("coin_id");
            if (listed.contains(id)) continue;
            String why = whyNot.get(id);
            if (why == null) {
                PathA.Result a = pathAAll.get(id);
                why = a != null && a.candidate() ? "路径 A 入选超过 30 个，按相对强度未进前 20"
                        : a != null && !a.notes().isEmpty() ? "不再满足路径 A：" + String.join("；", a.notes())
                        : a != null ? "不再满足路径 A（30/90 天超额不足 20 个百分点或长期不再弱于 BTC/ETH）"
                        : "不再满足筛选条件（今日未进入预筛或收入不再连增）";
            }
            out.add(new Eliminated(id, (String) r.get("name"), (String) r.get("grade"), why));
        }
        return out;
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
    public static List<String> bandSample(List<String> ranked) {
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
