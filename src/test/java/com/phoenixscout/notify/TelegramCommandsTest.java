package com.phoenixscout.notify;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Telegram 查询命令：只认 ps_ 前缀，不抢 TrendPilot 的 /holdings /status /help。 */
class TelegramCommandsTest {

    @Test
    void onlyPsPrefixedCommands() {
        assertEquals("/ps_holdings", TelegramCommands.command("/ps_holdings"));
        assertEquals("/ps_pnl", TelegramCommands.command(" /PS_PNL@PhoenixScoutBot extra"));
        assertNull(TelegramCommands.command("/holdings"), "TrendPilot 的命令不回应");
        assertNull(TelegramCommands.command("/status"));
        assertNull(TelegramCommands.command("hello"));
        assertNull(TelegramCommands.command(null));
        assertTrue(TelegramCommands.helpText().contains("/ps_holdings"));
        assertFalse(TelegramCommands.helpText().contains(" /holdings"));
    }

    static Map<String, Object> snap(List<Map<String, Object>> positions) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("startEquity", 5000.0);
        b.put("equityLive", 5123.45);
        b.put("returnPct", 2.47);
        b.put("cash", 2500.0);
        b.put("open", positions.size());
        b.put("unrealizedUsd", 123.45);
        b.put("realizedClosedUsd", 0.0);
        b.put("closedTrades", 0);
        b.put("positions", positions);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("portfolio", b);
        return s;
    }

    @Test
    void holdingsAndPnlTexts() {
        assertTrue(TelegramCommands.holdingsText(snap(List.of())).contains("当前空仓"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", "Xcoin（X）");
        p.put("entryPrice", 0.5);
        p.put("price", 0.55);
        p.put("priceSource", "实时");
        p.put("amountUsd", 2500.0);
        p.put("pnlUsd", 123.45);
        p.put("pnlPct", 4.94);
        p.put("stopLoss", 0.325);
        p.put("tp1", 1.0);
        p.put("openedDay", "2026-10-18");
        String h = TelegramCommands.holdingsText(snap(List.of(p)));
        assertTrue(h.contains("Xcoin") && h.contains("+123.45") && h.contains("+4.9%") && h.contains("不是真实持仓"), h);
        String pnl = TelegramCommands.pnlText(snap(List.of(p)));
        assertTrue(pnl.contains("权益 $5,123") && pnl.contains("浮动盈亏 +123.45") && pnl.contains("已实现盈亏 +0.00"), pnl);
        assertTrue(TelegramCommands.pnlText(Map.of()).contains("暂无数据"));
    }
}
