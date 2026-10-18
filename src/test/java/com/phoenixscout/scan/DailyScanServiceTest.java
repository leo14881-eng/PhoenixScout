package com.phoenixscout.scan;

import com.phoenixscout.config.PsProperties;
import com.phoenixscout.http.RateLimitedHttp;
import com.phoenixscout.llama.DefiLlamaClient;
import com.phoenixscout.market.CoinGeckoClient;
import com.phoenixscout.market.DailyPoint;
import com.phoenixscout.notify.TelegramNotifier;
import com.phoenixscout.report.ReportWriter;
import com.phoenixscout.snapshot.SnapshotService;
import com.phoenixscout.snapshot.SnapshotServiceTest;
import com.phoenixscout.store.PriceStore;
import com.phoenixscout.store.SqliteApiCache;
import com.phoenixscout.support.TestDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 端到端：假的 CoinGecko/DefiLlama 返回构造好的行情，验证整条扫描链路、缓存与报告。 */
class DailyScanServiceTest {

    static final LocalDate START = LocalDate.of(2025, 10, 17);
    static final LocalDate DAY = START.plusDays(365);   // 2026-10-17
    static final int MID = 287;                          // 2026-07-31
    static final int ALTS = 120;

    static double price(String id, int k) {
        double a, mid, end;
        switch (id) {
            case "bitcoin" -> { a = 100; mid = 60; end = 75; }
            case "ethereum" -> { a = 100; mid = 50; end = 62; }
            case "hero" -> { a = 100; mid = 15; end = 45; }
            case "tether" -> { return 1.0; }
            default -> { a = 100; mid = 45; end = 50; }
        }
        // hero 在中间日之前更早触底，最近 90 天强势反弹
        if (id.equals("hero")) {
            if (k <= MID) return a + (mid - a) * k / (double) MID;
            return mid + (end - mid) * (k - MID) / (double) (365 - MID);
        }
        if (k <= MID) return a + (mid - a) * k / (double) MID;
        return mid + (end - mid) * (k - MID) / (double) (365 - MID);
    }

    /** 成交额：最近 30 天 400 万美元，之前 250 万（量能比 1.6，满足 V2.6 放量与 200 万美元下限）。 */
    static double vol(int k) {
        return k > 335 ? 4e6 : 2.5e6;
    }

    static List<String> ids() {
        List<String> ids = new ArrayList<>(List.of("bitcoin", "ethereum", "tether"));
        for (int i = 0; i < ALTS; i++) ids.add("alt" + i);
        ids.add("hero");
        return ids;
    }

    static String marketsJson() {
        StringBuilder s = new StringBuilder("[");
        int rank = 1;
        for (String id : ids()) {
            double cap = switch (id) {
                case "bitcoin" -> 1.5e12;
                case "ethereum" -> 3e11;
                case "tether" -> 1e11;
                case "hero" -> 1e8;
                default -> 5e8;
            };
            if (s.length() > 1) s.append(',');
            s.append(String.format(Locale.ROOT, "{\"id\":\"%s\",\"symbol\":\"%s\",\"name\":\"%s\",\"market_cap_rank\":%d,"
                            + "\"current_price\":%f,\"market_cap\":%f,\"fully_diluted_valuation\":%f,\"circulating_supply\":1000,"
                            + "\"total_supply\":2000,\"max_supply\":null,\"total_volume\":12345}",
                    id, id, id, rank++, price(id, 365), cap, cap * 2));
        }
        return s.append(']').toString();
    }

    static String chartJson(String id, int days) {
        StringBuilder p = new StringBuilder(), c = new StringBuilder(), v = new StringBuilder();
        for (int k = 365 - days; k <= 365; k++) {
            long ts = START.plusDays(k).toEpochDay() * 86_400_000L;
            String sep = p.isEmpty() ? "" : ",";
            p.append(sep).append('[').append(ts).append(',').append(price(id, k)).append(']');
            c.append(sep).append('[').append(ts).append(',').append(price(id, k) * 1e6).append(']');
            v.append(sep).append('[').append(ts).append(',').append(vol(k)).append(']');
        }
        long now = DAY.toEpochDay() * 86_400_000L + 3_600_000L;
        p.append(",[").append(now).append(",999]");
        return "{\"prices\":[" + p + "],\"market_caps\":[" + c + "],\"total_volumes\":[" + v + "]}";
    }

    static String snapshotJson() {
        StringBuilder coins = new StringBuilder();
        StringBuilder top = new StringBuilder();
        for (String id : ids()) {
            if (!coins.isEmpty()) {
                coins.append(',');
                top.append(',');
            }
            top.append('"').append(id).append('"');
            coins.append(String.format(Locale.ROOT, "{\"id\":\"%s\",\"symbol\":\"%s\",\"name\":\"%s\",\"excluded_categories\":%s,"
                            + "\"2025-10-31\":{\"price\":%f,\"price_flag\":\"ok\",\"market_cap\":1},"
                            + "\"2026-07-31\":{\"price\":%f,\"price_flag\":\"ok\",\"market_cap\":1}}",
                    id, id, id, id.equals("tether") ? "[\"stablecoins\"]" : "[]", price(id, 14), price(id, MID)));
        }
        return "{\"version\":\"v1\",\"dates\":[\"2025-10-31\",\"2026-07-31\"],\"historical_top_k_ids\":[" + top
                + "],\"coins\":[" + coins + "]}";
    }

    @Test
    void endToEnd(@TempDir Path dir) throws Exception {
        var db = new TestDb(dir);
        var cache = new SqliteApiCache(db.jdbc);
        var prices = new PriceStore(db.jdbc, db.tx);
        // 种子历史（模拟 P0 归档）：到 2 天前为止
        for (String id : ids()) {
            List<DailyPoint> pts = new ArrayList<>();
            for (int k = 0; k <= 363; k++) {
                LocalDate d = START.plusDays(k);
                pts.add(new DailyPoint(d, price(id, k), price(id, k) * 1e6, vol(k)));
            }
            prices.saveDaily(id, pts, "p0-archive", Instant.now());
        }
        SnapshotServiceTest.write(dir.resolve("data/snapshot"), "v1", snapshotJson(), true);

        AtomicInteger cgCalls = new AtomicInteger();
        Pattern chart = Pattern.compile("/coins/([^/]+)/market_chart\\?.*days=(\\d+)");
        var cgHttp = new RateLimitedHttp("cg", 0, 2, (u, h) -> {
            cgCalls.incrementAndGet();
            Matcher m = chart.matcher(u);
            if (m.find()) return new RateLimitedHttp.Response(200, chartJson(m.group(1), Integer.parseInt(m.group(2))), u, Instant.now());
            if (u.contains("category=stablecoins") && u.contains("page=1"))
                return new RateLimitedHttp.Response(200, "[{\"id\":\"tether\"}]", u, Instant.now());
            if (u.contains("category=")) return new RateLimitedHttp.Response(200, "[]", u, Instant.now());
            if (u.contains("/coins/markets") && u.contains("page=1&") || u.endsWith("page=1"))
                return new RateLimitedHttp.Response(200, marketsJson(), u, Instant.now());
            return new RateLimitedHttp.Response(200, "[]", u, Instant.now());
        }, ms -> { });
        var llHttp = new RateLimitedHttp("ll", 0, 2, (u, h) -> new RateLimitedHttp.Response(200,
                u.contains("/protocols") ? "[]" : "{\"protocols\":[]}", u, Instant.now()), ms -> { });

        var props = new PsProperties(dir.resolve("data").toString(), "Asia/Ho_Chi_Minh", new PsProperties.Telegram("", ""),
                new PsProperties.Coingecko("http://cg", 0, null), new PsProperties.Defillama("http://ll", 0),
                new PsProperties.Scan("0 40 0 * * *", "UTC", 2500, 3e7, 1e9, 150, 100, false));
        var clock = Clock.fixed(DAY.atTime(0, 45).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var svc = new DailyScanService(props, new CoinGeckoClient("http://cg", cgHttp, null, cache),
                new DefiLlamaClient("http://ll", llHttp, cache), prices, db.jdbc,
                new SnapshotService(dir.resolve("data/snapshot")), new TelegramNotifier(props.telegram()),
                new ReportWriter(dir.resolve("data/reports")), clock);
        svc.setSimLedger(new com.phoenixscout.sim.SimLedger(db.jdbc));

        var o = svc.runToday();
        assertNotNull(o);
        assertEquals(DAY, o.scanDay());
        assertTrue(o.quality().problems().isEmpty(), o.quality().problems().toString());
        assertEquals(-0.40, o.benchmarks().btcLong(), 0.02);
        assertNotNull(o.benchmarks().benchB30());
        assertEquals(1, o.pathA().size(), "只有 hero 满足超跌转强");
        assertEquals("hero", o.pathA().get(0).coin().id());
        assertTrue(o.pathA().get(0).result().a1Pass());
        assertEquals(6, o.benchInfo().aTotal(), "tether 被排除，基准 A 取第 101–500 名山寨（每 4 个取 1 个）");
        assertNotNull(o.benchInfo().breadthTop100Long());
        assertEquals(o.quality().tracked(), o.quality().trackedWithLatest(), "增量补齐后全部有今日日线");
        assertTrue(Files.exists(o.reportFile()));
        String md = Files.readString(o.reportFile());
        assertTrue(md.contains("hero"));
        assertTrue(md.contains("今日 A 级：无符合条件的项目"));
        assertTrue(new ReportWriter(dir).telegramSummary(o).contains("超跌转强 1 个（只观察）"));
        assertTrue(o.picks().isEmpty(), "路径 A 降级为观察，不进入推荐");
        assertTrue(o.aList().isEmpty());
        assertTrue(o.bList().isEmpty());
        assertEquals(1, o.pathAWatch().size());
        var w = o.pathAWatch().get(0);
        assertEquals("hero", w.coinId());
        assertEquals("B", w.grade(), "没有研究、无法估值 → 最高 B 级");
        assertFalse(w.target().available(), "没有收入 → 无法估值，不用历史价格当目标");
        assertFalse(w.ev().available(), "回测样本不足 30 个 → 不输出期望值");
        assertFalse(w.quality().researched());
        assertTrue(o.simEvents().isEmpty());
        assertEquals(0, o.simSummary().open());
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM sim_trade", Integer.class), "观察名单不记台账");
        assertTrue(Files.exists(dir.resolve("data/sim/ledger.csv")));
        String tg = new ReportWriter(dir).telegramSummary(o);
        assertTrue(tg.contains("【A级】无"), tg);
        assertTrue(tg.contains("【B级观察】"));
        assertTrue(tg.contains("【路径A观察】"));
        assertTrue(tg.contains("hero"));
        assertTrue(tg.contains("策略尚未验证有效，仅供观察"), "回测没有证明有效时顶部必须提示");
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM bt_run", Integer.class), "首次扫描跑一次策略回测");
        assertEquals("ok", db.jdbc.queryForObject("SELECT status FROM scan_run WHERE scan_day = ?", String.class, DAY.toString()));

        // 同一天再跑：日线已是最新、行情和分类在缓存期内 → 不再请求第三方
        int before = cgCalls.get();
        var o2 = svc.runToday();
        assertNotNull(o2);
        assertEquals(before, cgCalls.get(), "第二次扫描应全部命中缓存");
    }
}
