package com.phoenixscout.market;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.TreeMap;

/** 对外暴露 market_chart 的解析（P0 归档导入复用同一套“只取 00:00 点”的规则）。 */
public final class CoinGeckoClientParser {
    private CoinGeckoClientParser() {
    }

    public static TreeMap<LocalDate, DailyPoint> parse(JsonNode body) {
        return CoinGeckoClient.parseChart(body);
    }
}
