package com.phoenixscout.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phoenixscout.config.PsProperties;
import com.phoenixscout.scan.DailyScanService;
import com.phoenixscout.sim.LiveSimService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Telegram 查询命令（只读，不做任何交易）：/holdings 模拟持仓、/pnl 模拟盈亏、/today 今日名单、/status 运行状态、/help。
 * PhoenixScout 用自己单独的机器人，命令不加前缀（用户 2026-10-18 要求短命令）；旧的 /ps_ 写法仍然认。
 * 启动时把命令注册到机器人菜单（setMyCommands），输入 / 就能点选。
 * <p>
 * 用 getUpdates 长轮询收消息。一个机器人只能有一个程序收消息，所以默认关闭（phoenixscout.telegram.commands-enabled=false）；
 * 只有给本项目单独建了机器人才打开。只回应配置里的 chat-id；遇到 429 按 retry_after 等待；回复经 TelegramNotifier 发出（带项目名前缀）。
 */
@Component
public class TelegramCommands {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommands.class);
    static final String OFFSET_KEY = "telegram.commands.offset";

    private final PsProperties.Telegram cfg;
    private final TelegramNotifier notifier;
    private final JdbcTemplate jdbc;
    private final LiveSimService sim;
    private final DailyScanService scan;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper om = new ObjectMapper();
    private volatile boolean running;
    private Thread thread;

    public TelegramCommands(PsProperties props, TelegramNotifier notifier, JdbcTemplate jdbc, LiveSimService sim, DailyScanService scan) {
        this.cfg = props.telegram();
        this.notifier = notifier;
        this.jdbc = jdbc;
        this.sim = sim;
        this.scan = scan;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (cfg == null || !cfg.commandsOn()) {
            log.info("Telegram 查询命令未开启（需要本项目单独的机器人，并设 phoenixscout.telegram.commands-enabled: true）");
            return;
        }
        running = true;
        registerMenu();
        thread = new Thread(this::loop, "ps-telegram-commands");
        thread.setDaemon(true);
        thread.start();
        log.info("Telegram 查询命令已开启");
    }

    /** 把命令注册到机器人菜单（失败只记日志）。 */
    void registerMenu() {
        try {
            String json = "{\"commands\":[{\"command\":\"holdings\",\"description\":\"模拟组合持仓\"},"
                    + "{\"command\":\"pnl\",\"description\":\"模拟盈亏\"},{\"command\":\"today\",\"description\":\"今日名单\"},"
                    + "{\"command\":\"status\",\"description\":\"运行状态\"},{\"command\":\"help\",\"description\":\"命令说明\"}]}";
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + cfg.botToken() + "/setMyCommands"))
                    .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) log.warn("注册 Telegram 命令菜单失败 HTTP {}", r.statusCode());
        } catch (Exception e) {
            log.warn("注册 Telegram 命令菜单出错：{}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    void loop() {
        long offset = readOffset();
        while (running) {
            try {
                String url = "https://api.telegram.org/bot" + cfg.botToken() + "/getUpdates?timeout=50&allowed_updates=%5B%22message%22%5D"
                        + (offset > 0 ? "&offset=" + offset : "");
                HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(70)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode j = om.readTree(r.body());
                if (r.statusCode() == 429) {
                    sleep(Math.max(5, j.path("parameters").path("retry_after").asLong(30)) * 1000L);
                    continue;
                }
                if (r.statusCode() == 409) {
                    // 另一个程序也在用这个机器人收消息：停止，避免互相抢（提醒一次）
                    log.warn("Telegram getUpdates 冲突（409）：这个机器人被别的程序占用，查询命令停止");
                    notifier.send("⚠️ 查询命令已停止：这个 Telegram 机器人正被别的程序接收消息。请给 PhoenixScout 单独建一个机器人。");
                    running = false;
                    return;
                }
                if (r.statusCode() != 200) {
                    log.warn("Telegram getUpdates HTTP {}", r.statusCode());
                    sleep(30_000);
                    continue;
                }
                for (JsonNode u : j.path("result")) {
                    offset = Math.max(offset, u.path("update_id").asLong() + 1);
                    JsonNode msg = u.path("message");
                    String chat = msg.path("chat").path("id").asText("");
                    if (!chat.equals(cfg.chatId())) continue;   // 只回应自己的会话
                    String reply = handle(msg.path("text").asText(""));
                    if (reply != null) notifier.send(reply);
                }
                writeOffset(offset);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Telegram 查询命令出错：{}", e.getClass().getSimpleName());
                sleep(30_000);
            }
        }
    }

    /** 处理一条消息，返回回复文本；不是本项目的命令返回 null（不回应，避免和别的项目抢答）。 */
    String handle(String text) {
        String cmd = command(text);
        if (cmd == null) return null;
        try {
            return switch (cmd) {
                case "/holdings" -> holdingsText(sim.snapshot());
                case "/pnl" -> pnlText(sim.snapshot());
                case "/today" -> {
                    // 先发名单，再把完整日报作为文件发出
                    notifier.send(todayText());
                    sendReport();
                    yield null;
                }
                case "/status" -> statusText();
                case "/help", "/start" -> helpText();
                default -> "不认识的指令。\n" + helpText();
            };
        } catch (Exception e) {
            return "查询失败：" + e.getClass().getSimpleName();
        }
    }

    /** 取命令名（去掉 @机器人名、参数和旧的 ps_ 前缀，转小写）；不是 / 开头返回 null。 */
    static String command(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (!t.startsWith("/")) return null;
        String c = t.split("\\s+")[0];
        int at = c.indexOf('@');
        if (at > 0) c = c.substring(0, at);
        c = c.toLowerCase(Locale.ROOT);
        if (c.startsWith("/ps_")) c = "/" + c.substring(4);
        return c.length() > 1 ? c : null;
    }

    static String helpText() {
        return """
                查询命令（只读，系统不做真实交易）：
                /holdings 模拟组合当前持仓（实时价）
                /pnl 模拟盈亏（已实现、浮动、总收益）
                /today 今日 A 级 / B 级观察名单
                /status 运行状态（版本、最近一次扫描）
                /help 本说明""";
    }

    @SuppressWarnings("unchecked")
    static String holdingsText(Map<String, Object> snap) {
        StringBuilder s = new StringBuilder("📋 模拟组合持仓（只记账，不是真实持仓）\n");
        Map<String, Object> b = (Map<String, Object>) snap.get("portfolio");
        List<Map<String, Object>> rows = b == null ? List.of() : (List<Map<String, Object>>) b.get("positions");
        if (rows == null || rows.isEmpty()) {
            s.append("当前空仓");
        } else {
            for (var p : rows) {
                s.append("\n· ").append(p.get("name")).append("｜入场 ").append(num(p.get("entryPrice"))).append(" → 现价 ")
                        .append(num(p.get("price"))).append("（").append(p.get("priceSource")).append("）")
                        .append("\n  投入 $").append(num(p.get("amountUsd"))).append("｜盈亏 ").append(signed(p.get("pnlUsd")))
                        .append(" 美元（").append(signedPct(p.get("pnlPct"))).append("）｜止损 ").append(num(p.get("stopLoss")))
                        .append(p.get("tp1") == null ? "" : "｜第一止盈 " + num(p.get("tp1")))
                        .append("｜").append(p.get("openedDay")).append(" 建仓");
            }
        }
        if (b != null) s.append("\n\n现金 $").append(num(b.get("cash"))).append("｜权益 $").append(num(b.get("equityLive")));
        if (snap.get("warning") != null) s.append("\n⚠️ ").append(snap.get("warning"));
        return s.toString();
    }

    @SuppressWarnings("unchecked")
    static String pnlText(Map<String, Object> snap) {
        Map<String, Object> b = (Map<String, Object>) snap.get("portfolio");
        if (b == null) return "💰 模拟盈亏：暂无数据";
        StringBuilder s = new StringBuilder("💰 模拟组合盈亏（起始 $").append(num(b.get("startEquity"))).append("，只记账）\n");
        s.append("权益 $").append(num(b.get("equityLive"))).append("（").append(signedPct(b.get("returnPct"))).append("）\n");
        s.append("浮动盈亏 ").append(signed(b.get("unrealizedUsd"))).append(" 美元（持仓 ").append(b.get("open")).append(" 个）\n");
        s.append("已实现盈亏 ").append(signed(b.get("realizedClosedUsd"))).append(" 美元（已平仓 ").append(b.get("closedTrades")).append(" 笔）");
        Map<String, Object> t = (Map<String, Object>) snap.get("track");
        if (t != null) {
            s.append("\n\n选币台账（每个推荐等额记一笔，检验选得准不准）：浮动 ").append(signed(t.get("unrealizedUsd")))
                    .append("，已实现 ").append(signed(t.get("realizedClosedUsd"))).append(" 美元，持仓 ").append(t.get("open")).append(" 个");
        }
        return s.toString();
    }

    String todayText() {
        List<Map<String, Object>> run = jdbc.queryForList("SELECT scan_day, status, summary FROM scan_run ORDER BY scan_day DESC LIMIT 1");
        if (run.isEmpty()) return "今天还没有扫描结果";
        String day = (String) run.get(0).get("scan_day");
        List<Map<String, Object>> recs = jdbc.queryForList("SELECT name, grade FROM rec_daily WHERE scan_day = ? ORDER BY grade, rank", day);
        StringBuilder s = new StringBuilder("📊 ").append(day).append(" 名单（状态 ").append(run.get(0).get("status")).append("）\n");
        appendGrade(s, recs, "A", "【A级】");
        appendGrade(s, recs, "B", "【B级观察】");
        s.append(java.nio.file.Files.isRegularFile(reportFile(day)) ? "\n完整日报（含接近入选、未入选原因）见下一条附件" : "\n（完整日报文件还没生成）");
        return s.toString();
    }

    static java.nio.file.Path reportFile(String day) {
        return java.nio.file.Path.of("data", "reports", day + ".md");
    }

    void sendReport() {
        List<String> d = jdbc.queryForList("SELECT scan_day FROM scan_run ORDER BY scan_day DESC LIMIT 1", String.class);
        if (!d.isEmpty()) notifier.sendDocument(reportFile(d.get(0)), " " + d.get(0) + " 完整日报");
    }

    static void appendGrade(StringBuilder s, List<Map<String, Object>> recs, String grade, String title) {
        s.append(title);
        var names = recs.stream().filter(r -> grade.equals(r.get("grade"))).map(r -> String.valueOf(r.get("name"))).toList();
        s.append(names.isEmpty() ? "无" : String.join("、", names)).append("\n");
    }

    String statusText() {
        String version = "";
        try {
            version = java.nio.file.Files.readString(java.nio.file.Path.of("app", "version.txt")).trim();
        } catch (Exception ignored) {
            // 没有版本文件（开发环境）
        }
        return "🟢 PhoenixScout 运行中" + (version.length() >= 7 ? "（版本 " + version.substring(0, 7) + "）" : "")
                + "\n最近一次扫描：" + (scan.lastFinish() == null ? "本次启动后还没有" : scan.lastFinish() + "，状态 " + scan.lastStatus())
                + (scan.isRunning() ? "\n扫描正在进行中" : "");
    }

    long readOffset() {
        try {
            List<String> v = jdbc.queryForList("SELECT v FROM kv WHERE k = ?", String.class, OFFSET_KEY);
            return v.isEmpty() ? 0 : Long.parseLong(v.get(0));
        } catch (Exception e) {
            return 0;
        }
    }

    void writeOffset(long offset) {
        if (offset > 0) jdbc.update("INSERT OR REPLACE INTO kv(k, v) VALUES (?, ?)", OFFSET_KEY, Long.toString(offset));
    }

    static String num(Object o) {
        if (!(o instanceof Number n)) return "—";
        double v = n.doubleValue();
        if (Math.abs(v) >= 100) return String.format(Locale.ROOT, "%,.0f", v);
        if (Math.abs(v) >= 1) return String.format(Locale.ROOT, "%.2f", v);
        return String.format(Locale.ROOT, "%.4g", v);
    }

    static String signed(Object o) {
        return o instanceof Number n ? String.format(Locale.ROOT, "%+,.2f", n.doubleValue()) : "—";
    }

    static String signedPct(Object o) {
        return o instanceof Number n ? String.format(Locale.ROOT, "%+.1f%%", n.doubleValue()) : "—";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
