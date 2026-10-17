package com.phoenixscout.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.phoenixscout.market.MarketCoin;
import com.phoenixscout.scan.DailyScanService;
import com.phoenixscout.scan.PathA;
import com.phoenixscout.scan.PathB;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 日报（Markdown + JSON）与 Telegram 摘要。缺失的数一律写“缺失”，不写 0。 */
public class ReportWriter {

    private final Path dir;
    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).findAndRegisterModules();

    public ReportWriter(Path reportDir) {
        this.dir = reportDir;
    }

    public Path write(DailyScanService.Outcome o) {
        try {
            Files.createDirectories(dir);
            Path md = dir.resolve(o.scanDay() + ".md");
            Files.writeString(md, markdown(o), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("candidates-" + o.scanDay() + ".json"), om.writeValueAsString(o), StandardCharsets.UTF_8);
            return md;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String pct(Double v) {
        return v == null ? "缺失" : String.format(Locale.ROOT, "%+.1f%%", v * 100);
    }

    public static String usd(Double v) {
        if (v == null) return "缺失";
        if (v >= 1e9) return String.format(Locale.ROOT, "$%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "$%.1fM", v / 1e6);
        return String.format(Locale.ROOT, "$%.0f", v);
    }

    static String mark(Boolean b) {
        return b == null ? "?" : b ? "✓" : "✗";
    }

    static String name(MarketCoin c) {
        return c == null ? "?" : c.name() + "（" + c.symbol().toUpperCase(Locale.ROOT) + "）";
    }

    public static String oneLine(DailyScanService.Outcome o) {
        return "路径A " + o.pathA().size() + "，路径B " + o.pathB().size() + "，新币 " + o.newPool().size()
                + "，状态 " + o.status();
    }

    String markdown(DailyScanService.Outcome o) {
        var b = o.benchmarks();
        var bi = o.benchInfo();
        var q = o.quality();
        StringBuilder s = new StringBuilder();
        s.append("# PhoenixScout 日报 ").append(o.scanDay()).append("\n\n");
        s.append("- 数据截止：").append(o.scanDay()).append(" 00:00 UTC 日线点（= ").append(o.scanDay().minusDays(1))
                .append(" UTC 日收盘价），来源 CoinGecko 免费接口\n");
        s.append("- 状态：").append(o.status()).append("\n");
        s.append(o.aGrades().isEmpty() ? "- **今日 A 级：无符合条件的项目**（候选须经研究层核实第十四条 9 项硬性条件才可评 A）\n\n"
                : "- **当前 A 级（14 天内研究核实）：" + String.join("、", o.aGrades()) + "**\n\n");

        s.append("## 数据质量\n\n| 项目 | 数值 |\n|---|---|\n");
        s.append("| CoinGecko 请求 成功/失败/限流 | ").append(q.cgOk()).append(" / ").append(q.cgFailed()).append(" / ").append(q.cgRateLimited()).append(" |\n");
        s.append("| DefiLlama 请求 成功/失败 | ").append(q.llamaOk()).append(" / ").append(q.llamaFailed()).append(" |\n");
        s.append("| 行情覆盖（目标前 ").append(q.universeTarget()).append("） | ").append(q.marketCoins()).append(" |\n");
        s.append("| 精确日线覆盖（有今日 00:00 点 / 跟踪数） | ").append(q.trackedWithLatest()).append(" / ").append(q.tracked()).append(" |\n");
        s.append("| 供应数据覆盖（流通量+总量都有） | ").append(q.supplyKnown()).append(" / ").append(q.marketCoins()).append(" |\n");
        s.append("| 基本面覆盖（DefiLlama 收入增长且映射到代币的协议） | ").append(q.pathBMapped()).append(" |\n");
        s.append("| 快照 | ").append(q.snapshotStatus()).append(" |\n");
        s.append("| 排除名单 | ").append(q.exclusionsFresh() ? "已更新（7 天缓存）" : "部分沿用上次结果").append(" |\n\n");
        if (!q.problems().isEmpty()) {
            s.append("**未完成或异常的部分：**\n\n");
            q.problems().forEach(p -> s.append("- ").append(p).append("\n"));
            s.append("\n");
        }

        s.append("## 基准\n\n| 区间 | BTC | ETH | 山寨中位数 |\n|---|---|---|---|\n");
        if (bi.baseDate() != null) {
            s.append("| 长期 ").append(bi.baseDate()).append("→").append(bi.midDate()).append(" | ").append(pct(b.btcLong()))
                    .append(" | ").append(pct(b.ethLong())).append(" | 基准A ").append(pct(b.benchALong())).append(" |\n");
            s.append("| 近期 ").append(bi.midDate()).append("→").append(o.scanDay()).append(" | ").append(pct(b.btcRecent()))
                    .append(" | ").append(pct(b.ethRecent())).append(" | 基准A ").append(pct(b.benchARecent())).append(" |\n");
        }
        s.append("| 滚动 30 天 | ").append(pct(b.btc30())).append(" | ").append(pct(b.eth30())).append(" | 基准B ").append(pct(b.benchB30())).append(" |\n");
        s.append("| 滚动 90 天 | ").append(pct(b.btc90())).append(" | ").append(pct(b.eth90())).append(" | 基准B ").append(pct(b.benchB90())).append(" |\n");
        s.append("| 滚动 180 天 | ").append(pct(b.btc180())).append(" | ").append(pct(b.eth180())).append(" | — |\n");
        s.append("| 滚动 365 天 | ").append(pct(b.btc365())).append(" | ").append(pct(b.eth365())).append(" | — |\n\n");
        s.append("基准 A（2025-10-31 第 101–500 名山寨币，每 4 个取 1 个）：成分 ").append(bi.aTotal()).append("，可计算 ").append(bi.aComputable())
                .append("，今日无精确日线 ").append(bi.aNoCurrentData()).append("。基准 B（当前第 101–500 名山寨币，每 4 个取 1 个）：成分 ")
                .append(bi.bTotal()).append("，可计算 ").append(bi.bComputable()).append("。两者分别报告，不混用。\n");
        s.append("前 100 名山寨币长期区间中位数（仅作市场广度参考）：").append(pct(bi.breadthTop100Long())).append("\n\n");

        s.append("## 路径 A：长期严重超跌后转强（").append(o.pathA().size()).append(" 个，B 级待研究）\n\n");
        s.append("条件（V2.3.1）：滚动 30/90 天同时跑赢 BTC、ETH、基准 B（A2，必要条件）+ 长期跑输 BTC 和 ETH + 没有出现更低的低点。A1（长期跌幅 ≥65% 且跑输 BTC、ETH，近期领先最强基准 20 个百分点）为参考加分。\n\n");
        if (o.pathA().isEmpty()) s.append("今日无。\n\n");
        else {
            s.append("| 币 | 市值 | FDV | 长期 | 近期 | 30天 | 90天 | 180天 | 365天 | A1 | 跌幅65–90% | 更高低点 | 上涨集中度 | 量能比 |\n");
            s.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (var c : o.pathA()) {
                PathA.Result r = c.result();
                s.append("| ").append(name(c.coin())).append(c.inPriorityRange() ? "" : " ⓘ").append(" | ").append(usd(c.coin().marketCap()))
                        .append(" | ").append(usd(c.coin().fdv())).append(" | ").append(pct(r.rLong())).append(" | ").append(pct(r.rRecent()))
                        .append(" | ").append(pct(r.r30())).append(" | ").append(pct(r.r90())).append(" | ").append(pct(r.r180()))
                        .append(" | ").append(pct(r.r365())).append(" | ").append(r.a1Pass() ? "✓" : "✗").append(" | ")
                        .append(r.drop65to90() ? "✓" : "✗").append(" | ").append(mark(r.higherLow())).append(" | ")
                        .append(r.top3Concentration() == null ? "缺失" : String.format(Locale.ROOT, "%.0f%%", r.top3Concentration() * 100))
                        .append(" | ").append(r.volumeRatio() == null ? "缺失" : String.format(Locale.ROOT, "%.2f", r.volumeRatio())).append(" |\n");
            }
            s.append("\nⓘ = 市值不在优先区间（3,000 万–10 亿美元）。上涨集中度 = 90 天里最好 3 天的贡献占比，越高越集中。量能比 = 近 30 天均量 / 之前 60 天均量。\n\n");
        }

        s.append("## 路径 B：收入连续两季增长（").append(o.pathB().size()).append(" 个，B 级待研究）\n\n");
        s.append("口径：DefiLlama 协议收入（不是持币人利润）；只覆盖 DeFi 协议，公链/AI/基础设施类没有收入数据，结果天然偏向 DeFi。\n\n");
        if (o.pathB().isEmpty()) s.append("今日无。\n\n");
        else {
            s.append("| 代币 | 协议 | 最近季度收入 | 上一季度 | 再上一季度 | 收入增速 | 币价 90 天 | 价格未反映 |\n|---|---|---|---|---|---|---|---|\n");
            for (PathB.Result r : o.pathB()) {
                s.append("| ").append(r.coinId()).append(" | ").append(r.protocol()).append(" | ").append(usd(r.q0())).append(" | ")
                        .append(usd(r.q1())).append(" | ").append(usd(r.q2())).append(" | ").append(pct(r.revenueGrowth())).append(" | ")
                        .append(pct(r.price90())).append(" | ").append(r.priceLagging() ? "✓" : "✗").append(" |\n");
            }
            s.append("\n");
        }

        s.append("## 路径 C 与新币观察池\n\n路径 C（早期高成长）需要研究层核实产品、用户、代币经济和审计，研究层上线后输出。\n\n");
        s.append("新币观察池（历史不足 90 天，不参与路径 A 排名）：").append(o.newPool().size()).append(" 个\n\n");
        for (var n : o.newPool()) {
            s.append("- ").append(name(n.coin())).append(" 市值 ").append(usd(n.coin().marketCap())).append("，最早日线 ")
                    .append(n.firstDay() == null ? "缺失" : n.firstDay().toString()).append("\n");
        }
        s.append("\n## 与上次比较\n\n- 新增：").append(o.added().isEmpty() ? "无" : String.join("、", o.added()))
                .append("\n- 移出：").append(o.dropped().isEmpty() ? "无" : String.join("、", o.dropped())).append("\n");
        return s.toString();
    }

    public String telegramSummary(DailyScanService.Outcome o) {
        var b = o.benchmarks();
        var q = o.quality();
        StringBuilder s = new StringBuilder();
        s.append("🔥 PhoenixScout 日报 ").append(o.scanDay()).append("\n");
        s.append(o.aGrades().isEmpty() ? "今日 A 级：无符合条件的项目（候选均为 B 级待研究）\n\n"
                : "当前 A 级：" + String.join("、", o.aGrades()) + "（理由见单独推送）\n\n");
        s.append("基准 30天/90天：BTC ").append(pct(b.btc30())).append("/").append(pct(b.btc90()))
                .append("｜ETH ").append(pct(b.eth30())).append("/").append(pct(b.eth90()))
                .append("｜山寨中位数B ").append(pct(b.benchB30())).append("/").append(pct(b.benchB90())).append("\n");
        if (o.benchInfo().baseDate() != null) {
            s.append("长期区间：BTC ").append(pct(b.btcLong())).append("｜ETH ").append(pct(b.ethLong()))
                    .append("｜基准A ").append(pct(b.benchALong())).append("\n");
        }
        s.append("\n路径A 超跌转强 ").append(o.pathA().size()).append(" 个");
        List<DailyScanService.CandidateA> top = o.pathA().stream().limit(8).toList();
        for (int i = 0; i < top.size(); i++) {
            var c = top.get(i);
            var r = c.result();
            s.append("\n").append(i + 1).append(". ").append(name(c.coin())).append(" ").append(usd(c.coin().marketCap()))
                    .append("｜长期 ").append(pct(r.rLong())).append("｜30天 ").append(pct(r.r30())).append(" 90天 ").append(pct(r.r90()))
                    .append(r.a1Pass() ? "｜A1✓" : "").append(Boolean.TRUE.equals(r.higherLow()) ? "｜更高低点✓" : "");
        }
        if (o.pathA().size() > top.size()) s.append("\n…其余见报告");
        s.append("\n\n路径B 收入连增 ").append(o.pathB().size()).append(" 个");
        o.pathB().stream().limit(5).forEach(r -> s.append("\n· ").append(r.protocol()).append("（").append(r.coinId())
                .append("）收入季增 ").append(pct(r.revenueGrowth())).append("｜币价90天 ").append(pct(r.price90()))
                .append(r.priceLagging() ? "｜价格未反映✓" : ""));
        s.append("\n\n新币观察池 ").append(o.newPool().size()).append(" 个");
        if (!o.added().isEmpty() || !o.dropped().isEmpty()) {
            s.append("\n变化：新增 ").append(o.added().size()).append("，移出 ").append(o.dropped().size());
        }
        s.append("\n\n数据：行情 ").append(q.marketCoins()).append("/").append(q.universeTarget()).append("，精确日线 ")
                .append(q.trackedWithLatest()).append("/").append(q.tracked()).append("，CoinGecko 成功 ").append(q.cgOk())
                .append(" 失败 ").append(q.cgFailed()).append(" 限流 ").append(q.cgRateLimited());
        if (!q.problems().isEmpty()) {
            s.append("\n⚠️ ");
            s.append(String.join("；", q.problems().stream().limit(4).toList()));
        }
        s.append("\n报告：data\\reports\\").append(o.scanDay()).append(".md");
        return s.toString();
    }
}
