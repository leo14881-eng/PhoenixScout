package com.phoenixscout.notify;

import com.phoenixscout.config.PsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Telegram 推送。每条消息开头带项目名【PhoenixScout】；长消息按行切成多条（单条上限 4096，分条时标 1/3 这样的序号）；token 不写日志。 */
public class TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    static final int LIMIT = 3900;
    static final String TAG = "【PhoenixScout】";

    private final PsProperties.Telegram cfg;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public TelegramNotifier(PsProperties.Telegram cfg) {
        this.cfg = cfg;
    }

    public boolean configured() {
        return cfg != null && cfg.configured();
    }

    /** 发送成功返回 true；未配置或失败返回 false（只记日志，不抛出，不影响扫描）。 */
    public boolean send(String text) {
        if (!configured()) {
            log.info("Telegram 未配置，跳过推送：{}", text.length() > 80 ? text.substring(0, 80) + "…" : text);
            return false;
        }
        boolean all = true;
        for (String part : withTag(text)) {
            all &= sendOne(part);
        }
        return all;
    }

    /** 切分后给每条加项目名；多条时加序号。 */
    static List<String> withTag(String text) {
        List<String> parts = split(text);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            out.add(TAG + (parts.size() > 1 ? "（" + (i + 1) + "/" + parts.size() + "）" : "") + "\n" + parts.get(i));
        }
        return out;
    }

    private boolean sendOne(String part) {
        String form = "chat_id=" + enc(cfg.chatId()) + "&disable_web_page_preview=true&text=" + enc(part);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + cfg.botToken() + "/sendMessage"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return true;
                log.warn("Telegram 推送失败 HTTP {}", r.statusCode());
                if (r.statusCode() != 429 && r.statusCode() < 500) return false;
            } catch (Exception e) {
                log.warn("Telegram 推送异常：{}", e.getClass().getSimpleName());
            }
            try {
                Thread.sleep(5000L * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String l = line + "\n";
            while (l.length() > LIMIT) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(l.substring(0, LIMIT));
                l = l.substring(LIMIT);
            }
            if (cur.length() + l.length() > LIMIT) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(l);
        }
        String last = cur.toString().stripTrailing();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
