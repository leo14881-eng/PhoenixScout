package com.phoenixscout;

import com.phoenixscout.job.ScanScheduler;
import com.phoenixscout.scan.DailyScanService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "phoenixscout.data-dir=target/cmd-data",
        "phoenixscout.scan.catch-up-on-start=false",
        "phoenixscout.scan.cron=0 0 0 1 1 *",
        "server.port=0",
        "logging.file.name=target/cmd-data/test.log"})
class CommandFileTest {

    @BeforeAll
    static void dirs() throws Exception {
        Files.createDirectories(Path.of("target/cmd-data/commands"));
    }

    @MockitoBean
    DailyScanService scan;

    @Autowired
    ScanScheduler scheduler;

    @Test
    void scanRequestFileTriggersScanOnce() throws Exception {
        Path f = Path.of("target/cmd-data/commands/scan.request");
        Files.writeString(f, "");
        scheduler.commands();
        verify(scan, times(1)).runToday();
        assertFalse(Files.exists(f), "命令文件处理后删除");
        scheduler.commands();
        verify(scan, times(1)).runToday();
    }
}
