package com.phoenixscout.scan;

/**
 * 今日推荐（V2.6）：A 级最多 3 个 + B 级观察最多 5 个，按期望值排序。
 * takeProfit = 估值目标（卖一半），无法估值为 null；longTarget 暂不使用（null）；
 * stopLoss = 入场价 −35% 与近 90 天最低价中更低者；ev = 期望值（统计估计），样本不足或无法估值为 null。
 */
public record Pick(String coinId, String name, String path, String grade, Integer quality, boolean researched,
                   double price, Double takeProfit, Double longTarget, double stopLoss, Double ev, Double upside,
                   double downside, String reason) {

    public static final double STOP_DROP = 0.35;

    public static double stopFor(double price, Double low90) {
        double s = price * (1 - STOP_DROP);
        return low90 == null ? s : Math.min(s, low90);
    }
}
