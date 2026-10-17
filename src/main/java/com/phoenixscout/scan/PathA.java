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
 *   <li>A2 滚动窗口（主要依据）：30/90 天同时跑赢 BTC、ETH、基准 B；180/365 天只观察。</li>
 *   <li>A1 长期条件（V2.3.1 单一标准）：长期区间跌幅 ≥ 65% 且同时跑输 BTC、ETH。</li>
 *   <li>进入研究队列：A2 通过 + 长期跑输 BTC 和 ETH + 没有出现更低的低点。A1 为参考加分。</li>
 *   <li>A3 反弹质量只计算指标供研究层和报告使用，不单独淘汰。</li>
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
                         Double volumeRatio, List<String> notes) { }

    public static final double MARGIN = 0.20;

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
            a2 = r30 > best30 && r90 > best90;
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
        Double volRatio = null;
        var v30 = Returns.avgVolume(h, latest.minusDays(29), latest);
        var v60 = Returns.avgVolume(h, latest.minusDays(90), latest.minusDays(30));
        if (v30.isPresent() && v60.isPresent() && v60.get() > 0) volRatio = v30.get() / v60.get();
        // V2.3.1：出现更低的低点视为熊市反弹，不进入研究；无法判断（数据不足）不淘汰
        boolean candidate = Boolean.TRUE.equals(a2) && longWeak && !Boolean.FALSE.equals(higherLow);
        if (Boolean.TRUE.equals(a2) && longWeak && Boolean.FALSE.equals(higherLow)) notes.add("近 30 天出现更低的低点，视为熊市反弹");

        return new Result(coinId, rLong, rRecent, r30, r90, r180, r365, a1Long, a1Recent, a1Pass, a2, longWeak,
                candidate, drop, conc, higherLow, volRatio, notes);
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
