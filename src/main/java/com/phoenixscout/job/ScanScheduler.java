package com.phoenixscout.job;

import com.phoenixscout.scan.DailyScanService;
import com.phoenixscout.snapshot.SnapshotService;
import com.phoenixscout.store.ArchiveImporter;
import com.phoenixscout.store.SqliteApiCache;
import com.phoenixscout.notify.TelegramNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 定时任务：每日扫描（默认 UTC 00:40）。启动时：导入 P0 归档种子、校验快照；
 * 如果今天的扫描还没成功跑过（比如电脑刚开机、错过了定时），启动后补跑一次。
 */
@Component
public class ScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduler.class);

    private final DailyScanService scan;
    private final ArchiveImporter importer;
    private final SnapshotService snapshots;
    private final SqliteApiCache cache;
    private final TelegramNotifier tg;
    private final JdbcTemplate jdbc;
    private final Path dataDir;
    private final boolean catchUp;
    private final com.phoenixscout.snapshot.P0ArchiveService p0;
    private volatile boolean startupDone;

    public ScanScheduler(DailyScanService scan, ArchiveImporter importer, SnapshotService snapshots, SqliteApiCache cache,
                         TelegramNotifier tg, JdbcTemplate jdbc, Path dataDir, com.phoenixscout.config.PsProperties props,
                         com.phoenixscout.snapshot.P0ArchiveService p0) {
        this.catchUp = props.scan().catchUpOnStart();
        this.p0 = p0;
        this.scan = scan;
        this.importer = importer;
        this.snapshots = snapshots;
        this.cache = cache;
        this.tg = tg;
        this.jdbc = jdbc;
        this.dataDir = dataDir;
    }

    public boolean startupDone() {
        return startupDone;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(this::startup, "ps-startup");
        t.setDaemon(true);
        t.start();
    }

    void startup() {
        try {
            importer.importAll(dataDir.resolve("raw").resolve("p0-archive").resolve("history"));
            var s = snapshots.load();
            log.info("快照状态：{}", s.message());
            if (!s.available() && catchUp && !p0.snapshotExists()
                    && com.phoenixscout.snapshot.P0ArchiveService.windowOpen(LocalDate.now(ZoneOffset.UTC))) {
                startP0();
            } else if (!s.available()) {
                tg.send("⚠️ PhoenixScout 启动检查：" + s.message() + "。基准 A 与路径 A1 会停止计算，其余照常。");
            }
            cache.purgeOlderThan(Duration.ofDays(30));
        } catch (Exception e) {
            log.warn("启动检查出错：{}", e.getMessage());
        } finally {
            startupDone = true;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        boolean after = Instant.now().atZone(ZoneOffset.UTC).getHour() > 0
                || Instant.now().atZone(ZoneOffset.UTC).getMinute() >= 40;
        List<String> st = jdbc.queryForList("SELECT status FROM scan_run WHERE scan_day = ?", String.class, today.toString());
        boolean doneToday = !st.isEmpty() && (st.get(0).equals("ok") || st.get(0).equals("partial"));
        if (catchUp && after && !doneToday) {
            log.info("今天（{}）的扫描还没完成，启动后补跑", today);
            scan.runToday();
        }
    }

    /** 后台归档 P0 并生成快照（可续跑，耗时数小时，不阻塞每日扫描）。 */
    void startP0() {
        Thread t = new Thread(() -> {
            try {
                log.info("开始 P0 历史快照归档（后台）");
                String report = p0.run(LocalDate.now(ZoneOffset.UTC));
                if (report != null) {
                    importer.importAll(dataDir.resolve("raw").resolve("p0-archive").resolve("history"));
                    tg.send(report);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("P0 归档出错", e);
                tg.send("⚠️ PhoenixScout 历史快照归档出错：" + e.getMessage() + "。服务重启后会从断点续跑。");
            }
        }, "ps-p0-archive");
        t.setDaemon(true);
        t.start();
    }

    public String p0State() {
        return p0.state();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.phoenixscout.research.ResearchService research;

    /** 每 10 分钟处理研究层新写出的结果。 */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT2M")
    public void ingestResearch() {
        try {
            research.ingest();
        } catch (Exception e) {
            log.warn("处理研究结果出错：{}", e.getMessage());
        }
    }

    /**
     * 本机命令文件（每分钟检查一次 data/commands/）：scan.request → 立即重跑今天的扫描；research.request → 立即处理研究结果。
     * 方便远程维护时不需要操作桌面。处理后删除命令文件。
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void commands() {
        java.nio.file.Path dir = dataDir.resolve("commands");
        try {
            java.nio.file.Path scanReq = dir.resolve("scan.request");
            if (java.nio.file.Files.deleteIfExists(scanReq)) {
                log.info("收到命令：重跑今天的扫描");
                scan.runToday();
            }
            java.nio.file.Path simReset = dir.resolve("sim.reset");
            if (java.nio.file.Files.deleteIfExists(simReset)) {
                log.info("收到命令：清空模拟仓（重新按规则建仓）");
                jdbc.update("DELETE FROM sim_position");
            }
            java.nio.file.Path resReq = dir.resolve("research.request");
            if (java.nio.file.Files.deleteIfExists(resReq)) {
                log.info("收到命令：处理研究结果");
                research.ingest();
            }
        } catch (Exception e) {
            log.warn("处理命令文件出错：{}", e.getMessage());
        }
    }

    @Scheduled(cron = "${phoenixscout.scan.cron}", zone = "${phoenixscout.scan.cron-zone}")
    public void daily() {
        scan.runToday();
    }
}
