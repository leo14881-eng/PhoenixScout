package com.phoenixscout.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-18：Telegram 多了一个两参数构造器时，配置里的 commands-enabled 读不进来，查询命令一直没开。 */
class PsPropertiesBindingTest {

    @Test
    void commandsEnabledIsReadFromConfig() {
        var src = new MapConfigurationPropertySource(Map.of(
                "phoenixscout.telegram.bot-token", "123:abc",
                "phoenixscout.telegram.chat-id", "42",
                "phoenixscout.telegram.commands-enabled", "true"));
        PsProperties p = new Binder(src).bind("phoenixscout", PsProperties.class).get();
        assertTrue(p.telegram().commandsOn());
        var off = new MapConfigurationPropertySource(Map.of(
                "phoenixscout.telegram.bot-token", "123:abc", "phoenixscout.telegram.chat-id", "42"));
        assertFalse(new Binder(off).bind("phoenixscout", PsProperties.class).get().telegram().commandsOn(), "默认关闭");
    }
}
