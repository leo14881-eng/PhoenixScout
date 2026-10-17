package com.phoenixscout.market;

/** /coins/markets 的一行。数值缺失为 null，不填零。 */
public record MarketCoin(String id, String symbol, String name, Integer rank, Double price, Double marketCap,
                         Double fdv, Double circulating, Double totalSupply, Double maxSupply, Double volume24h) {
}
