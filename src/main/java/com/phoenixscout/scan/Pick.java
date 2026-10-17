package com.phoenixscout.scan;

/**
 * 今日推荐（盈亏比最高的前 10 个）。长线口径（V2.5）：
 * takeProfit = 第一目标（卖一半）；longTarget = 长期目标（路径 B 基准 36 个月价格，路径 A 为空：剩余一半用回撤止盈）；
 * stopLoss = 入场价 −35% 与近 90 天最低价中更低者。ratio = 第一目标涨幅 ÷ 到防守价的跌幅。
 */
public record Pick(String coinId, String name, String path, double price, double takeProfit, Double longTarget,
                   double stopLoss, double ratio, double upside, double downside, String reason) {

    public static final double STOP_DROP = 0.35;

    public static double stopFor(double price, Double low90) {
        double s = price * (1 - STOP_DROP);
        return low90 == null ? s : Math.min(s, low90);
    }
}
