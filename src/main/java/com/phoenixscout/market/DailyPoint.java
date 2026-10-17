package com.phoenixscout.market;

import java.time.LocalDate;

/** UTC 00:00 时间戳点（= 前一 UTC 日收盘价）。 */
public record DailyPoint(LocalDate day, Double price, Double marketCap, Double volume) {
}
