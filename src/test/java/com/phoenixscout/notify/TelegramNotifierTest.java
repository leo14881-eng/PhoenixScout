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
    void unconfiguredDoesNotSend() {
        var n = new TelegramNotifier(new PsProperties.Telegram("", ""));
        assertFalse(n.configured());
        assertFalse(n.send("hi"));
    }
}
