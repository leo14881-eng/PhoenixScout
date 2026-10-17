package com.phoenixscout.config;

import com.phoenixscout.http.ApiCache;
import com.phoenixscout.http.RateLimitedHttp;
import com.phoenixscout.llama.DefiLlamaClient;
import com.phoenixscout.market.CoinGeckoClient;
import com.phoenixscout.notify.TelegramNotifier;
import com.phoenixscout.report.ReportWriter;
import com.phoenixscout.scan.DailyScanService;
import com.phoenixscout.snapshot.SnapshotService;
import com.phoenixscout.store.ArchiveImporter;
import com.phoenixscout.store.PriceStore;
import com.phoenixscout.store.SqliteApiCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    Path dataDir(PsProperties p) throws IOException {
        Path d = Path.of(p.dataDir());
        Files.createDirectories(d);
        return d;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SqliteApiCache apiCache(JdbcTemplate jdbc) {
        return new SqliteApiCache(jdbc);
    }

    @Bean
    CoinGeckoClient coinGeckoClient(PsProperties p, ApiCache cache) {
        var cfg = p.coingecko();
        return new CoinGeckoClient(cfg.baseUrl(), new RateLimitedHttp("coingecko", cfg.minIntervalMs()),
                cfg.demoKeyFile() == null ? null : Path.of(cfg.demoKeyFile()), cache);
    }

    @Bean
    DefiLlamaClient defiLlamaClient(PsProperties p, ApiCache cache) {
        return new DefiLlamaClient(p.defillama().baseUrl(), new RateLimitedHttp("defillama", p.defillama().minIntervalMs()), cache);
    }

    @Bean
    PriceStore priceStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        return new PriceStore(jdbc, tx);
    }

    @Bean
    ArchiveImporter archiveImporter(JdbcTemplate jdbc, PriceStore prices) {
        return new ArchiveImporter(jdbc, prices);
    }

    @Bean
    SnapshotService snapshotService(Path dataDir) {
        return new SnapshotService(dataDir.resolve("snapshot"));
    }

    @Bean
    TelegramNotifier telegramNotifier(PsProperties p) {
        return new TelegramNotifier(p.telegram());
    }

    @Bean
    ReportWriter reportWriter(Path dataDir) {
        return new ReportWriter(dataDir.resolve("reports"));
    }

    @Bean
    DailyScanService dailyScanService(PsProperties p, CoinGeckoClient cg, DefiLlamaClient llama, PriceStore prices,
                                      JdbcTemplate jdbc, SnapshotService snaps, TelegramNotifier tg, ReportWriter rw, Clock clock) {
        return new DailyScanService(p, cg, llama, prices, jdbc, snaps, tg, rw, clock);
    }
}
