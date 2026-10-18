package com.phoenixscout.notify;

import com.phoenixscout.config.PsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelegramNotifierTest {

    @Test
    void splitKeepsAllTextUnderLimit() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("第 ").append(i).append(" 行 abcdefghij\n");
        sb.append("x".repeat(9000));
        var parts = TelegramNotifier.split(sb.toString());
        assertTrue(parts.size() > 2);
        parts.forEach(p -> assertTrue(p.length() <= TelegramNotifier.LIMIT + 1));
        assertEquals(sb.toString().replace("\n", ""), String.join("", parts).replace("\n", ""));
    }

    @Test
    void everyMessageStartsWithProjectName() {
        var one = TelegramNotifier.withTag("你好");
        assertEquals(java.util.List.of("【PhoenixScout】\n你好"), one);
        var many = TelegramNotifier.withTag("行\n".repeat(3000));
        assertTrue(many.size() > 1);
        for (int i = 0; i < many.size(); i++) {
            assertTrue(many.get(i).startsWith("【PhoenixScout】（" + (i + 1) + "/" + many.size() + "）\n"), many.get(i).substring(0, 30));
            assertTrue(many.get(i).length() <= 4096);
        }
    }

    @Test
    void unconfiguredDoesNotSend() {
        var n = new TelegramNotifier(new PsProperties.Telegram("", "", false));
        assertFalse(n.configured());
        assertFalse(n.send("hi"));
    }
}
