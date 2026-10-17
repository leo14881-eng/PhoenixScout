package com.phoenixscout.scan;

import com.phoenixscout.market.DailyPoint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;

/** 收益与统计的纯函数。只用精确日期的点，缺任何一端就返回空，绝不拿邻近日期替代。 */
public final class Returns {

    private Returns() {
    }

    public static Optional<Double> price(NavigableMap<LocalDate, DailyPoint> h, LocalDate d) {
        if (h == null) return Optional.empty();
        DailyPoint p = h.get(d);
        return p == null || p.price() == null || p.price() <= 0 ? Optional.empty() : Optional.of(p.price());
    }

    public static Optional<Double> ret(NavigableMap<LocalDate, DailyPoint> h, LocalDate from, LocalDate to) {
        var a = price(h, from);
        var b = price(h, to);
        return a.isPresent() && b.isPresent() ? Optional.of(b.get() / a.get() - 1) : Optional.empty();
    }

    public static Optional<Double> ret(Double fromPrice, Double toPrice) {
        return fromPrice == null || toPrice == null || fromPrice <= 0 || toPrice <= 0
                ? Optional.empty() : Optional.of(toPrice / fromPrice - 1);
    }

    public static Optional<Double> rolling(NavigableMap<LocalDate, DailyPoint> h, LocalDate latest, int days) {
        return ret(h, latest.minusDays(days), latest);
    }

    public static Optional<Double> median(Collection<Double> values) {
        List<Double> v = new ArrayList<>(values);
        v.removeIf(x -> x == null || x.isNaN());
        if (v.isEmpty()) return Optional.empty();
        v.sort(Double::compare);
        int n = v.size();
        return Optional.of(n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2);
    }

    /** 区间内的日收益（对数），只用连续两天都有价格的日子。 */
    public static List<Double> dailyLogReturns(NavigableMap<LocalDate, DailyPoint> h, LocalDate from, LocalDate to) {
        List<Double> out = new ArrayList<>();
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            var a = price(h, d.minusDays(1));
            var b = price(h, d);
            if (a.isPresent() && b.isPresent()) out.add(Math.log(b.get() / a.get()));
        }
        return out;
    }

    /** 上涨集中度：最好的 3 天贡献占区间总对数收益的比例（总收益 ≤ 0 时为空）。越接近或超过 1 越集中。 */
    public static Optional<Double> top3Concentration(NavigableMap<LocalDate, DailyPoint> h, LocalDate from, LocalDate to) {
        List<Double> r = dailyLogReturns(h, from, to);
        double total = r.stream().mapToDouble(Double::doubleValue).sum();
        if (r.size() < 10 || total <= 0) return Optional.empty();
        double top3 = r.stream().sorted((a, b) -> Double.compare(b, a)).limit(3).mapToDouble(Double::doubleValue).sum();
        return Optional.of(top3 / total);
    }

    /** 区间 [from, to] 内的最低价，缺点超过一半返回空。 */
    public static Optional<Double> minPrice(NavigableMap<LocalDate, DailyPoint> h, LocalDate from, LocalDate to) {
        double min = Double.MAX_VALUE;
        int have = 0, total = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            total++;
            var p = price(h, d);
            if (p.isPresent()) {
                have++;
                min = Math.min(min, p.get());
            }
        }
        return have * 2 >= total && have > 0 ? Optional.of(min) : Optional.empty();
    }

    /** 区间平均成交量，缺点超过一半返回空。 */
    public static Optional<Double> avgVolume(NavigableMap<LocalDate, DailyPoint> h, LocalDate from, LocalDate to) {
        double sum = 0;
        int have = 0, total = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            total++;
            DailyPoint p = h.get(d);
            if (p != null && p.volume() != null) {
                have++;
                sum += p.volume();
            }
        }
        return have * 2 >= total && have > 0 ? Optional.of(sum / have) : Optional.empty();
    }
}
