package com.phoenixscout.scan;

import com.phoenixscout.market.DailyPoint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * 路径 A 策略回测（V2.6 第七条，每周运行）与期望值的概率来源（第三条）。纯计算。
 *
 * <p>做法：对历史上每一天，只用当天及以前的日线跑一遍 V2.6 路径 A 筛选（排名按当天市值，基准 B = 当天第 101–500 名每 4 个取 1 个；
 * 长期区间 = 2025-10-31 → 当天前 90 天，避免用未来数据）。同一个币 30 天内重复入选只算第一次（一个“样本”）。
 * <p>已知偏差（报告必须写明）：币池只含本地有日线的币（2026-10 归档时仍在前 2,500 名的币 + 之后每天跟踪的币），死币不在内，结果偏乐观。
 */
public final class StrategyBacktest {

    private StrategyBacktest() {
    }

    public static final LocalDate LONG_BASE = LocalDate.of(2025, 10, 31);
    public static final double CAP_MIN = 3e7 / 3, CAP_MAX = 1e9 * 3;
    public static final int DEDUPE_DAYS = 30;
    public static final int EV_HORIZON = 90;
    public static final int MIN_SAMPLES = 30;
    static final int[] HORIZONS = {30, 90, 180};

    public record Signal(String coinId, LocalDate day, double price, double strength) { }

    /** 每天的入选（已按强度排序、最多 20 个），以及去重后的样本。 */
    public record Signals(Map<LocalDate, List<Signal>> byDay, List<Signal> episodes, Map<LocalDate, List<String>> benchB) { }

    public static Signals signals(Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist, Set<String> excluded,
                                  List<String> benchA, LocalDate from, LocalDate to) {
        Map<LocalDate, List<Signal>> byDay = new TreeMap<>();
        Map<LocalDate, List<String>> benchByDay = new HashMap<>();
        List<Signal> episodes = new ArrayList<>();
        Map<String, LocalDate> lastSignal = new HashMap<>();
        var bh = hist.get("bitcoin");
        var eh = hist.get("ethereum");
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            final LocalDate d = day;
            List<String> ranked = new ArrayList<>();
            Map<String, Double> cap = new HashMap<>();
            for (var e : hist.entrySet()) {
                String id = e.getKey();
                if (excluded.contains(id) || id.equals("bitcoin") || id.equals("ethereum")) continue;
                DailyPoint p = e.getValue().get(d);
                if (p == null || p.price() == null || p.marketCap() == null || p.marketCap() <= 0) continue;
                ranked.add(id);
                cap.put(id, p.marketCap());
            }
            ranked.sort(Comparator.comparingDouble((String id) -> cap.get(id)).reversed());
            if (ranked.size() > 2500) ranked = new ArrayList<>(ranked.subList(0, 2500));
            List<String> benchB = DailyScanService.bandSample(ranked);
            benchByDay.put(d, benchB);
            var bm = benchmarks(hist, benchA, benchB, d);
            Double best30 = PathA.maxAll(bm.btc30(), bm.eth30(), bm.benchB30());
            Double best90 = PathA.maxAll(bm.btc90(), bm.eth90(), bm.benchB90());
            if (best30 == null || best90 == null || bh == null || eh == null) continue;
            LocalDate mid = d.minusDays(90);
            List<Signal> today = new ArrayList<>();
            for (String id : ranked) {
                double c = cap.get(id);
                if (c < CAP_MIN || c > CAP_MAX) continue;
                var h = hist.get(id);
                if (h.firstKey().isAfter(d.minusDays(120))) continue;
                // 快速预筛：30/90 天达不到门槛的直接跳过（与 PathA 同口径）
                Double r30 = Returns.rolling(h, d, 30).orElse(null);
                Double r90 = Returns.rolling(h, d, 90).orElse(null);
                if (r30 == null || r90 == null || r30 < best30 + PathA.MARGIN || r90 < best90 + PathA.MARGIN) continue;
                var r = PathA.evaluate(id, h, d, Returns.price(h, LONG_BASE).orElse(null), Returns.price(h, mid).orElse(null), bm);
                if (!r.candidate()) continue;
                today.add(new Signal(id, d, Returns.price(h, d).orElseThrow(), r.strength() == null ? 0 : r.strength()));
            }
            today.sort(Comparator.comparingDouble(Signal::strength).reversed());
            if (today.size() > DailyScanService.MAX_A_KEEP_WHEN_CROWDED && today.size() > DailyScanService.A_CROWDED) {
                today = new ArrayList<>(today.subList(0, DailyScanService.MAX_A_KEEP_WHEN_CROWDED));
            }
            byDay.put(d, today);
            for (Signal s : today) {
                LocalDate prev = lastSignal.get(s.coinId());
                if (prev == null || prev.isBefore(d.minusDays(DEDUPE_DAYS))) episodes.add(s);
                lastSignal.put(s.coinId(), d);
            }
        }
        return new Signals(byDay, episodes, benchByDay);
    }

    /** 与生产相同口径的基准（长期区间用 2025-10-31 → 当天前 90 天）。 */
    public static PathA.Benchmarks benchmarks(Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist, List<String> benchA,
                                              List<String> benchB, LocalDate day) {
        LocalDate mid = day.minusDays(90);
        var bh = hist.get("bitcoin");
        var eh = hist.get("ethereum");
        List<Double> aLong = new ArrayList<>(), aRecent = new ArrayList<>(), b30 = new ArrayList<>(), b90 = new ArrayList<>();
        for (String id : benchA) {
            Returns.ret(hist.get(id), LONG_BASE, mid).ifPresent(aLong::add);
            Returns.ret(hist.get(id), mid, day).ifPresent(aRecent::add);
        }
        for (String id : benchB) {
            Returns.rolling(hist.get(id), day, 30).ifPresent(b30::add);
            Returns.rolling(hist.get(id), day, 90).ifPresent(b90::add);
        }
        return new PathA.Benchmarks(Returns.ret(bh, LONG_BASE, mid).orElse(null), Returns.ret(eh, LONG_BASE, mid).orElse(null),
                half(aLong, benchA.size()), Returns.ret(bh, mid, day).orElse(null), Returns.ret(eh, mid, day).orElse(null),
                half(aRecent, benchA.size()), Returns.rolling(bh, day, 30).orElse(null), Returns.rolling(eh, day, 30).orElse(null),
                half(b30, benchB.size()), Returns.rolling(bh, day, 90).orElse(null), Returns.rolling(eh, day, 90).orElse(null),
                half(b90, benchB.size()), Returns.rolling(bh, day, 180).orElse(null), Returns.rolling(eh, day, 180).orElse(null),
                Returns.rolling(bh, day, 365).orElse(null), Returns.rolling(eh, day, 365).orElse(null));
    }

    static Double half(List<Double> v, int total) {
        return total > 0 && v.size() * 2 >= total ? Returns.median(v).orElse(null) : null;
    }

    // ───────────────────────── 期望值 ─────────────────────────

    /** 期望值（统计估计）。n 为可用样本数（入选后有满 90 天日线的样本）。 */
    public record Ev(Integer n, Double pTarget, Double pStop, Double ev, String note) {
        public boolean available() {
            return ev != null;
        }
    }

    /**
     * 期望值 = P(90 天内先到目标涨幅) × 目标涨幅 − P(90 天内先到防守跌幅) × 防守跌幅。
     * P 用历史样本按收盘价“先碰到哪个”统计；两个都没碰到的样本不计收益（记为 0）。样本不足 30 个时不输出。
     */
    public static Ev expectedValue(List<Signal> episodes, Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist,
                                   LocalDate today, Double upside, double downside) {
        if (upside == null) return new Ev(null, null, null, null, "无法估值，没有目标涨幅");
        int n = 0, hitUp = 0, hitDown = 0;
        for (Signal s : episodes) {
            if (s.day().plusDays(EV_HORIZON).isAfter(today)) continue;
            var h = hist.get(s.coinId());
            if (h == null) continue;
            n++;
            double up = s.price() * (1 + upside), down = s.price() * (1 - downside);
            for (LocalDate d = s.day().plusDays(1); !d.isAfter(s.day().plusDays(EV_HORIZON)); d = d.plusDays(1)) {
                var p = Returns.price(h, d);
                if (p.isEmpty()) continue;
                if (p.get() >= up) {
                    hitUp++;
                    break;
                }
                if (p.get() <= down) {
                    hitDown++;
                    break;
                }
            }
        }
        if (n < MIN_SAMPLES) return new Ev(n, null, null, null, "样本不足（" + n + " 个，需 ≥" + MIN_SAMPLES + "）");
        double pu = hitUp / (double) n, pd = hitDown / (double) n;
        return new Ev(n, pu, pd, pu * upside - pd * downside,
                String.format(Locale.ROOT, "统计估计：%d 个历史样本，90 天内先到目标 %.0f%%、先到防守 %.0f%%", n, pu * 100, pd * 100));
    }

    // ───────────────────────── 每周回测统计 ─────────────────────────

    public record HorizonStat(int days, int n, double avgReturn, double winRate, Double excessBtc, Double excessEth,
                              Double excessAlt, double avgMaxDrawdown) { }

    public record Report(LocalDate from, LocalDate to, int signalDays, int episodes, int coinsCovered,
                         List<HorizonStat> horizons, double collapsedPct, boolean validated, String verdict) { }

    /** 统计：每个样本入场后 30/90/180 天收益、相对 BTC/ETH/山寨中位数的超额、期间最大回撤；归零（跌 90% 以上）或数据中断比例。 */
    public static Report report(Signals sig, Map<String, ? extends NavigableMap<LocalDate, DailyPoint>> hist,
                                LocalDate from, LocalDate to, LocalDate dataEnd) {
        List<HorizonStat> hs = new ArrayList<>();
        var bh = hist.get("bitcoin");
        var eh = hist.get("ethereum");
        for (int days : HORIZONS) {
            List<Double> ret = new ArrayList<>(), exB = new ArrayList<>(), exE = new ArrayList<>(), exA = new ArrayList<>(), mdd = new ArrayList<>();
            for (Signal s : sig.episodes()) {
                LocalDate end = s.day().plusDays(days);
                if (end.isAfter(dataEnd)) continue;
                var h = hist.get(s.coinId());
                var r = Returns.ret(h, s.day(), end);
                if (r.isEmpty()) continue;
                ret.add(r.get());
                Returns.ret(bh, s.day(), end).ifPresent(b -> exB.add(r.get() - b));
                Returns.ret(eh, s.day(), end).ifPresent(b -> exE.add(r.get() - b));
                List<Double> alts = new ArrayList<>();
                for (String a : sig.benchB().getOrDefault(s.day(), List.of())) Returns.ret(hist.get(a), s.day(), end).ifPresent(alts::add);
                if (alts.size() >= 10) exA.add(r.get() - Returns.median(alts).orElseThrow());
                mdd.add(maxDrawdown(h, s.day(), end));
            }
            if (ret.isEmpty()) continue;
            hs.add(new HorizonStat(days, ret.size(), avg(ret), ret.stream().filter(x -> x > 0).count() / (double) ret.size(),
                    exB.isEmpty() ? null : avg(exB), exE.isEmpty() ? null : avg(exE), exA.isEmpty() ? null : avg(exA), avg(mdd)));
        }
        int collapsed = 0, counted = 0;
        for (Signal s : sig.episodes()) {
            var h = hist.get(s.coinId());
            if (h == null) continue;
            counted++;
            Double low = null;
            for (var p : h.tailMap(s.day(), false).values()) if (p.price() != null) low = low == null ? p.price() : Math.min(low, p.price());
            boolean gone = h.lastKey().isBefore(dataEnd.minusDays(14)) || (low != null && low <= s.price() * 0.1);
            if (gone) collapsed++;
        }
        HorizonStat h90 = hs.stream().filter(x -> x.days() == 90).findFirst().orElse(null);
        boolean validated = h90 != null && h90.n() >= MIN_SAMPLES && h90.excessAlt() != null && h90.excessAlt() > 0
                && h90.excessBtc() != null && h90.excessBtc() > 0;
        String verdict = h90 == null || h90.n() < MIN_SAMPLES ? "样本不足，策略尚未验证有效"
                : validated ? "入选后 90 天平均跑赢 BTC 和山寨中位数" : "入选后 90 天没有跑赢 BTC 或山寨中位数，策略尚未验证有效";
        return new Report(from, to, (int) sig.byDay().values().stream().filter(l -> !l.isEmpty()).count(), sig.episodes().size(),
                (int) hist.size(), hs, counted == 0 ? 0 : collapsed * 100.0 / counted, validated, verdict);
    }

    static double maxDrawdown(NavigableMap<LocalDate, DailyPoint> h, LocalDate from, LocalDate to) {
        double peak = 0, mdd = 0;
        for (var p : h.subMap(from, true, to, true).values()) {
            if (p.price() == null) continue;
            peak = Math.max(peak, p.price());
            mdd = Math.min(mdd, p.price() / peak - 1);
        }
        return mdd;
    }

    static double avg(Collection<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public static Map<String, Object> toMap(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", r.from().toString());
        m.put("to", r.to().toString());
        m.put("signalDays", r.signalDays());
        m.put("episodes", r.episodes());
        m.put("coinsCovered", r.coinsCovered());
        m.put("collapsedPct", r.collapsedPct());
        m.put("validated", r.validated());
        m.put("verdict", r.verdict());
        List<Map<String, Object>> hz = new ArrayList<>();
        for (var h : r.horizons()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("days", h.days());
            o.put("n", h.n());
            o.put("avgReturn", h.avgReturn());
            o.put("winRate", h.winRate());
            o.put("excessBtc", h.excessBtc());
            o.put("excessEth", h.excessEth());
            o.put("excessAlt", h.excessAlt());
            o.put("avgMaxDrawdown", h.avgMaxDrawdown());
            hz.add(o);
        }
        m.put("horizons", hz);
        return m;
    }
}
