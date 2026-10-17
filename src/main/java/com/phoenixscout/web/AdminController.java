package com.phoenixscout.web;

import com.phoenixscout.scan.DailyScanService;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 本机管理接口（服务只监听 127.0.0.1）：平滑停机（自动更新用）、手动触发扫描。 */
@RestController
public class AdminController {

    private static final String HEADER = "X-PhoenixScout-Admin";
    private final ApplicationContext ctx;
    private final DailyScanService scan;

    public AdminController(ApplicationContext ctx, DailyScanService scan) {
        this.ctx = ctx;
        this.scan = scan;
    }

    @PostMapping("/api/admin/shutdown")
    public ResponseEntity<Map<String, Object>> shutdown(@RequestHeader(value = HEADER, required = false) String h) {
        if (!"shutdown".equals(h)) return ResponseEntity.status(403).body(Map.of("ok", false));
        Thread t = new Thread(() -> System.exit(SpringApplication.exit(ctx, () -> 0)), "ps-shutdown");
        t.setDaemon(false);
        t.start();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/admin/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestHeader(value = HEADER, required = false) String h) {
        if (!"scan".equals(h)) return ResponseEntity.status(403).body(Map.of("ok", false));
        if (scan.isRunning()) return ResponseEntity.ok(Map.of("ok", false, "message", "扫描已在进行"));
        Thread t = new Thread(scan::runToday, "ps-manual-scan");
        t.setDaemon(true);
        t.start();
        return ResponseEntity.ok(Map.of("ok", true, "message", "已开始扫描"));
    }
}
