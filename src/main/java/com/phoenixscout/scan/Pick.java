package com.phoenixscout.scan;

/**
 * 今日推荐（盈亏比最高的前 10 个）。ratio 用于排序：收益 ÷ 风险，风险至少按 10% 计，避免价格贴着低点时比值虚高。
 * price = 今日 00:00 UTC 收盘价；takeProfit / stopLoss 为模拟仓使用的止盈止损价。
 */
public record Pick(String coinId, String name, String path, double price, double takeProfit, double stopLoss,
                   double ratio, double upside, double downside, String reason) {

    public static final double MIN_RISK = 0.10;

    public static double rankRatio(double up, double down) {
        return up / Math.max(down, MIN_RISK);
    }
}
