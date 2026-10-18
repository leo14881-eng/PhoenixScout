package com.phoenixscout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "phoenixscout")
public record PsProperties(String dataDir, String zone, Telegram telegram, Coingecko coingecko,
                           Defillama defillama, Scan scan) {

    /**
     * @param commandsEnabled 是否用 getUpdates 接收查询命令（/ps_holdings 等）。默认关：一个机器人只能有一个程序收消息，
     *                        和别的项目共用机器人时必须保持关闭，换成本项目单独的机器人后再打开。
     */
    public record Telegram(String botToken, String chatId, Boolean commandsEnabled) {
        public Telegram(String botToken, String chatId) {
            this(botToken, chatId, false);
        }

        public boolean configured() {
            return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
        }

        public boolean commandsOn() {
            return configured() && Boolean.TRUE.equals(commandsEnabled);
        }
    }

    public record Coingecko(String baseUrl, long minIntervalMs, String demoKeyFile) { }

    public record Defillama(String baseUrl, long minIntervalMs) { }

    public record Scan(String cron, String cronZone, int universeTopN, double capMinUsd, double capMaxUsd,
                       int maxCandidateFetch, int benchmarkSize, boolean catchUpOnStart) { }
}
