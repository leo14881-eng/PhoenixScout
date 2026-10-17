package com.phoenixscout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "phoenixscout")
public record PsProperties(String dataDir, String zone, Telegram telegram, Coingecko coingecko,
                           Defillama defillama, Scan scan) {

    public record Telegram(String botToken, String chatId) {
        public boolean configured() {
            return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
        }
    }

    public record Coingecko(String baseUrl, long minIntervalMs, String demoKeyFile) { }

    public record Defillama(String baseUrl, long minIntervalMs) { }

    public record Scan(String cron, String cronZone, int universeTopN, double capMinUsd, double capMaxUsd,
                       int maxCandidateFetch, int benchmarkSize, boolean catchUpOnStart) { }
}
