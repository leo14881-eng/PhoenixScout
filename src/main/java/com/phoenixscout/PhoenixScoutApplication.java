package com.phoenixscout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** PhoenixScout：只找币、给理由、推送消息；不实现任何交易功能。 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class PhoenixScoutApplication {
    public static void main(String[] args) throws java.io.IOException {
        // SQLite 不会自己建目录：先建好数据和日志目录（全新安装时）
        java.nio.file.Files.createDirectories(java.nio.file.Path.of(System.getProperty("phoenixscout.data-dir", "data")));
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("logs"));
        SpringApplication.run(PhoenixScoutApplication.class, args);
    }
}
