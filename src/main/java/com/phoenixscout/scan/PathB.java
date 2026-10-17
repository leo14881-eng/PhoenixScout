package com.phoenixscout.scan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;

/**
 * 路径 B：基本面领先但价格尚未反映（第十二条）——定量部分。
 * 用 DefiLlama 协议收入（dailyRevenue 口径，是协议收入，不是持币人利润），按最近三个完整 91 天“季度”比较：
 * 连续两个季度增长 = 通过；再看币价 90 天收益是否明显落后收入增速（落后 20 个百分点以上标记“价格未反映”）。
 * 用户、市场份额、价值捕获、催化剂等需研究层核实。
 */
public final class PathB {

    private PathB() {
    }

    public record Result(String coinId, String protocol, Double q0, Double q1, Double q2, Double revenueGrowth,
                         Double price90, boolean pass, boolean priceLagging, List<String> notes) { }

    static final int Q = 91;
    static final int MIN_DAYS = 80;

    public static Result evaluate(String coinId, String protocol, NavigableMap<LocalDate, Double> rev, LocalDate latest,
                                  Double price90) {
        List<String> notes = new ArrayList<>();
        Double q0 = quarter(rev, latest, 0);
        Double q1 = quarter(rev, latest, 1);
        Double q2 = quarter(rev, latest, 2);
        if (q0 == null || q1 == null || q2 == null) {
            notes.add("收入数据不足三个完整季度");
            return new Result(coinId, protocol, q0, q1, q2, null, price90, false, false, notes);
        }
        boolean pass = q2 > 0 && q1 > q2 && q0 > q1;
        Double growth = q1 > 0 ? q0 / q1 - 1 : null;
        boolean lagging = pass && growth != null && price90 != null && price90 < growth - PathA.MARGIN;
        if (price90 == null) notes.add("90 天价格缺失");
        return new Result(coinId, protocol, q0, q1, q2, growth, price90, pass, lagging, notes);
    }

    /** 第 k 个季度（0 = 最近）：[latest-91(k+1)+1, latest-91k]，有数据的天数不足 80 天返回 null。 */
    static Double quarter(NavigableMap<LocalDate, Double> rev, LocalDate latest, int k) {
        LocalDate to = latest.minusDays((long) Q * k);
        LocalDate from = to.minusDays(Q - 1);
        var sub = rev.subMap(from, true, to, true);
        if (sub.size() < MIN_DAYS) return null;
        return sub.values().stream().mapToDouble(Double::doubleValue).sum();
    }
}
