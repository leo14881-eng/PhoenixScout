package com.phoenixscout.scan;

import com.phoenixscout.market.DailyPoint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/**
 * 路径 A：长期严重超跌后转强（第十一条）。纯计算，不访问网络。
 * <ul>
 *   <li>A1 固定区间（参考）：长期 = 快照基准日→中间日；近期 = 中间日→最新完整 UTC 日线。</li>
 *   <li>A2 滚动窗口（主要依据，V2.6）：30 天、90 天收益都要 ≥ max(BTC, ETH, 基准 B) + 20 个百分点；180/365 天只观察。</li>
 *   <li>V2.6 反弹质量（必须全部满足，缺数据 = 未判定 = 不入选）：最大单日涨幅占 30 天涨幅 &lt; 50%；
 *       近 30 天日均成交额 ≥ 之前 90 天日均的 1.3 倍；近 30 天日均成交额 ≥ 200 万美元；
 *       收盘价站上 90 日均线，且 90 日均线不低于 10 天前的 99%（走平或向上）。成交额为 CoinGecko total_volume。</li>
 *   <li>A1 长期条件（V2.3.1 单一标准）：长期区间跌幅 ≥ 65% 且同时跑输 BTC、ETH。</li>
 *   <li>入选：A2 通过 + 长期跑输 BTC 和 ETH + 没有出现更低的低点 + V2.6 反弹质量全部满足。A1 为参考加分。</li>
 * </ul>
 */
public final class PathA {

    private PathA() {
    }

    /** 同一口径下的基准收益；缺失为 null。 */
    public record Benchmarks(Double btcLong, Double ethLong, Double benchALong,
                             Double btcRecent, Double ethRecent, Double benchARecent,
                             Double btc30, Double eth30, Double benchB30,
                             Double btc90, Double eth90, Double benchB90,
                             Double btc180, Double eth180, Double btc365, Double eth365) { }

    public record Result(String coinId, Double rLong, Double rRecent, Double r30, Double r90, Double r180, Double r365,
                         Boolean a1LongOk, Boolean a1RecentOk, boolean a1Pass, Boolean a2Pass, boolean longWeak,
                         boolean candidate, boolean drop65to90, Double top3Concentration, Boolean higherLow,
                         Double volumeRatio, Double excess30, Double excess90, Double maxDayShare, Double avgVolume30,
                         Double ma90, Boolean aboveMa90, Boolean ma90FlatOrUp, Double strength, List<String> notes) { }

    public static final double MARGIN = 0.20;
    public static final double MAX_DAY_SHARE = 0.50;
    public static final double VOLUME_RATIO = 1.3;
    public static final double MIN_VOLUME_USD = 2_000_000;
    public static final double MA_SLOPE_TOLERANCE = 0.99;

    /**
     * @param basePrice 快照基准日价格（可为 null：快照不可用或该币不在快照里）
     * @param midPrice  快照中间日价格
     */
    public static Result evaluate(String coinId, NavigableMap<LocalDate, DailyPoint> h, LocalDate latest,
                                  Double basePrice, Double midPrice, Benchmarks b) {
        List<String> notes = new ArrayList<>();
        Double latestPrice = Returns.price(h, latest).orElse(null);
        if (latestPrice == null) notes.add("最新完整日线缺失");
        Double rLong = Returns.ret(basePrice, midPrice).orElse(null);
        Double rRecent = Returns.ret(midPrice, latestPrice).orElse(null);
        Double r30 = Returns.rolling(h, latest, 30).orElse(null);
        Double r90 = Returns.rolling(h, latest, 90).orElse(null);
        Double r180 = Returns.rolling(h, latest, 180).orElse(null);
        Double r365 = Returns.rolling(h, latest, 365).orElse(null);

        // A1
        Boolean a1Long = null;
        Double worse = minOf(b.btcLong(), b.ethLong());
        if (rLong != null && worse != null) a1Long = rLong <= -0.65 && rLong < worse;
        Boolean a1Recent = null;
        Double best = maxAll(b.btcRecent(), b.ethRecent(), b.benchARecent());
        if (rRecent != null && best != null) a1Recent = rRecent > best + MARGIN;
        if (b.benchARecent() == null) notes.add("基准 A 不可用，A1 近期条件未判定");
        boolean a1Pass = Boolean.TRUE.equals(a1Long) && Boolean.TRUE.equals(a1Recent);

        // A2：30 天与 90 天都要同时跑赢三个基准；任何一个缺失就是“未判定”，不是“不通过”
        Boolean a2 = null;
        Double best30 = maxAll(b.btc30(), b.eth30(), b.benchB30());
        Double best90 = maxAll(b.btc90(), b.eth90(), b.benchB90());
        if (r30 != null && r90 != null && best30 != null && best90 != null) {
            a2 = r30 >= best30 + MARGIN && r90 >= best90 + MARGIN;
        } else {
            notes.add("30/90 天数据或基准缺失，A2 未判定");
        }

        // 长期弱势：优先用快照固定区间；不在快照里时用 365 天滚动收益
        boolean longWeak;
        if (rLong != null && worse != null) {
            longWeak = rLong < worse;
        } else {
            Double worse365 = minOf(b.btc365(), b.eth365());
            longWeak = r365 != null && worse365 != null && r365 < worse365;
            if (rLong == null) notes.add("不在快照或快照价格缺失，长期弱势用 365 天滚动收益判断");
        }
        boolean drop = rLong != null && rLong <= -0.65 && rLong >= -0.90;
        if (rLong != null && rLong < -0.90) notes.add("长期跌幅超过 90%，归零风险高");

        // A3 指标
        Double conc = Returns.top3Concentration(h, latest.minusDays(90), latest).orElse(null);
        Boolean higherLow = null;
        var recentLow = Returns.minPrice(h, latest.minusDays(29), latest);
        var priorLow = Returns.minPrice(h, latest.minusDays(90), latest.minusDays(30));
        if (recentLow.isPresent() && priorLow.isPresent()) higherLow = recentLow.get() > priorLow.get();
        // V2.6 量能：近 30 天日均 vs 之前 90 天（第 31–120 天）日均
        Double volRatio = null;
        var v30 = Returns.avgVolume(h, latest.minusDays(29), latest);
        var v90 = Returns.avgVolume(h, latest.minusDays(119), latest.minusDays(30));
        if (v30.isPresent() && v90.isPresent() && v90.get() > 0) volRatio = v30.get() / v90.get();
        Double maxDayShare = maxDayShare(h, latest, r30);
        Double ma90 = ma(h, latest, 90);
        Double ma90Before = ma(h, latest.minusDays(10), 90);
        Boolean above = ma90 == null || latestPrice == null ? null : latestPrice > ma90;
        Boolean flatUp = ma90 == null || ma90Before == null ? null : ma90 >= ma90Before * MA_SLOPE_TOLERANCE;
        Double ex30 = r30 == null || best30 == null ? null : r30 - best30;
        Double ex90 = r90 == null || best90 == null ? null : r90 - best90;
        Double strength = ex30 == null || ex90 == null ? null : ex30 + ex90;

        boolean base = Boolean.TRUE.equals(a2) && longWeak;
        // V2.3.1：出现更低的低点视为熊市反弹；无法判断（数据不足）不淘汰
        if (base && Boolean.FALSE.equals(higherLow)) notes.add("近 30 天出现更低的低点，视为熊市反弹");
        boolean quality = true;
        if (base) {
            if (maxDayShare == null) { quality = false; notes.add("30 天涨幅不为正或日线不足，上涨集中度未判定"); }
            else if (maxDayShare >= MAX_DAY_SHARE) { quality = false; notes.add("上涨集中在一天（最大单日占 30 天涨幅 ≥50%）"); }
            if (volRatio == null) { quality = false; notes.add("成交额历史不足 120 天，量能未判定"); }
            else if (volRatio < VOLUME_RATIO) { quality = false; notes.add("量能未放大（近 30 天 < 之前 90 天的 1.3 倍）"); }
            if (v30.isEmpty()) quality = false;
            else if (v30.get() < MIN_VOLUME_USD) { quality = false; notes.add("流动性不足（近 30 天日均成交额 < 200 万美元）"); }
            if (above == null || flatUp == null) { quality = false; notes.add("90 日均线数据不足，未判定"); }
            else if (!above) { quality = false; notes.add("收盘价未站上 90 日均线"); }
            else if (!flatUp) { quality = false; notes.add("90 日均线仍在下行"); }
        }
        boolean candidate = base && !Boolean.FALSE.equals(higherLow) && quality;

        return new Result(coinId, rLong, rRecent, r30, r90, r180, r365, a1Long, a1Recent, a1Pass, a2, longWeak,
                candidate, drop, conc, higherLow, volRatio, ex30, ex90, maxDayShare, v30.orElse(null), ma90, above, flatUp,
                strength, notes);
    }

    /** 最近 30 天最大单日涨幅 ÷ 30 天总涨幅；30 天收益不为正或缺日线返回 null。 */
    static Double maxDayShare(NavigableMap<LocalDate, DailyPoint> h, LocalDate latest, Double r30) {
        if (r30 == null || r30 <= 0) return null;
        double best = 0;
        int have = 0;
        for (LocalDate d = latest.minusDays(29); !d.isAfter(latest); d = d.plusDays(1)) {
            var p0 = Returns.price(h, d.minusDays(1));
            var p1 = Returns.price(h, d);
            if (p0.isEmpty() || p1.isEmpty()) continue;
            have++;
            best = Math.max(best, p1.get() / p0.get() - 1);
        }
        return have < 20 ? null : best / r30;
    }

    /** n 日简单均线（以 latest 为最后一天），缺点超过 10% 返回 null。 */
    static Double ma(NavigableMap<LocalDate, DailyPoint> h, LocalDate latest, int n) {
        double sum = 0;
        int have = 0;
        for (LocalDate d = latest.minusDays(n - 1); !d.isAfter(latest); d = d.plusDays(1)) {
            var p = Returns.price(h, d);
            if (p.isPresent()) {
                have++;
                sum += p.get();
            }
        }
        return have < n * 0.9 ? null : sum / have;
    }

    static Double minOf(Double... v) {
        Double m = null;
        for (Double x : v) {
            if (x == null) return null;
            m = m == null ? x : Math.min(m, x);
        }
        return m;
    }

    /** 全部存在才返回最大值，任何一个缺失返回 null（缺基准就不判定）。 */
    static Double maxAll(Double... v) {
        Double m = null;
        for (Double x : v) {
            if (x == null) return null;
            m = m == null ? x : Math.max(m, x);
        }
        return m;
    }
}
