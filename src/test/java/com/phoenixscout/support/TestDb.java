package com.phoenixscout.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;

/** 测试用 SQLite（临时文件 + 正式迁移脚本）。 */
public final class TestDb {
    public final JdbcTemplate jdbc;
    public final TransactionTemplate tx;

    public TestDb(Path dir) {
        var ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("t.db"), true);
        ds.setDriverClassName("org.sqlite.JDBC");
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
}
