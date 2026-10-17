package com.phoenixscout.web;

import com.phoenixscout.job.ScanScheduler;
import com.phoenixscout.scan.DailyScanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 守护脚本用：ok=false 表示卡住（扫描运行超过 4 小时），守护会重启服务。 */
@RestController
public class HealthController {

    private final DailyScanService scan;
    private final ScanScheduler scheduler;

    public HealthController(DailyScanService scan, ScanScheduler scheduler) {
        this.scan = scan;
        this.scheduler = scheduler;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        boolean stuck = scan.isRunning() && scan.lastStart() != null
                && Duration.between(scan.lastStart(), Instant.now()).toHours() >= 4;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", !stuck);
        m.put("startupDone", scheduler.startupDone());
        m.put("scanRunning", scan.isRunning());
        m.put("lastScanStart", scan.lastStart() == null ? null : scan.lastStart().toString());
        m.put("lastScanFinish", scan.lastFinish() == null ? null : scan.lastFinish().toString());
        m.put("lastScanStatus", scan.lastStatus());
        m.put("lastScanError", scan.lastError());
        m.put("p0Archive", scheduler.p0State());
        return m;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        return health();
    }
}
