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

    public Path dir() {
        return dir;
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

        List<com.phoenixscout.scan.Pick> picks = o.picks() == null ? List.of() : o.picks();
        s.append("## 今日推荐（盈亏比最高 ").append(picks.size()).append(" 个）\n\n");
        s.append("长线口径（V2.5）：盈亏比 = 第一目标涨幅 ÷ 到防守价的跌幅；防守价 = 入场价 −35% 与近 90 天最低价中更低者。")
                .append("路径 A 第一目标 = 回到长期起点价，之后剩余一半从最高收盘回撤 40% 止盈；路径 B 第一目标 = 基准 12 个月价、长期目标 = 基准 36 个月价；")
                .append("当前市值/收入远低于同类的暂不推荐。\n\n");
        if (!picks.isEmpty()) {
            s.append("| # | 币 | 路径 | 现价 | 第一目标 | 长期目标 | 防守 | 盈亏比 | 理由 |\n|---|---|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < picks.size(); i++) {
                var k = picks.get(i);
                s.append("| ").append(i + 1).append(" | ").append(k.name()).append(" | ").append(k.path()).append(" | ")
                        .append(RiskReward.price(k.price())).append(" | ").append(RiskReward.price(k.takeProfit()))
                        .append(String.format(Locale.ROOT, "（%+.0f%%）", k.upside() * 100)).append(" | ")
                        .append(k.longTarget() == null ? "回撤 40% 止盈" : RiskReward.price(k.longTarget())).append(" | ")
                        .append(RiskReward.price(k.stopLoss())).append(String.format(Locale.ROOT, "（%+.0f%%）", -k.downside() * 100))
                        .append(String.format(Locale.ROOT, " | %.1f | ", k.ratio())).append(k.reason()).append(" |\n");
            }
            s.append("\n");
        }
        if (o.simSummary() != null) {
            var sm = o.simSummary();
            s.append("## 模拟组合（只记账，不下真实订单）\n\n");
            s.append("规则（V2.5 长线）：本金 1 万美元，按盈亏比补满空位（最多 10 个），每笔风险 = 权益 1%（盈亏比高的放大），金额 = 风险 ÷ 跌幅；")
                    .append("到第一目标卖一半并保本，剩余到长期目标或从最高收盘回撤 40% 卖出；收盘跌破防守价止损；翻倍后保本；最长 3 年；")
                    .append("研究层判定逻辑失效即卖出；买卖各计 0.3% 成本。另有“选币台账”每个推荐等额记一笔，用来检验选得准不准。明细：data/sim/ledger.csv\n\n");
            for (var e : o.simEvents()) {
                if (!com.phoenixscout.sim.SimLedger.PORTFOLIO.equals(e.book())) continue;
                s.append("- ").append(e.kind()).append(" ").append(e.name()).append(" ").append(RiskReward.price(e.price()));
                if (e.pnlUsd() != null) s.append(String.format(Locale.ROOT, "，盈亏 %+.0f 美元（%+.1f%%）", e.pnlUsd(), e.pnlPct() * 100));
                if (e.note() != null) s.append("，").append(e.note());
                s.append("\n");
            }
            s.append(String.format(Locale.ROOT, "\n权益 %.0f 美元，现金 %.0f；持仓 %d 个（成本 %.0f，浮动 %+.0f）；已平仓 %d 笔（盈 %d / 亏 %d），已实现 %+.0f 美元\n\n",
                    sm.equity() + sm.unrealized(), sm.cash(), sm.open(), sm.invested(), sm.unrealized(), sm.closed(), sm.wins(), sm.losses(), sm.realized()));
        }

        s.append("## 路径 A：长期严重超跌后转强（").append(o.pathA().size()).append(" 个，B 级待研究）\n\n");
        s.append("条件（V2.3.1）：滚动 30/90 天同时跑赢 BTC、ETH、基准 B（A2，必要条件）+ 长期跑输 BTC 和 ETH + 没有出现更低的低点。A1（长期跌幅 ≥65% 且跑输 BTC、ETH，近期领先最强基准 20 个百分点）为参考加分。\n\n");
        if (o.pathA().isEmpty()) s.append("今日无。\n\n");
        else {
            s.append("| 币 | 市值 | FDV | 长期 | 近期 | 30天 | 90天 | 180天 | 365天 | A1 | 跌幅65–90% | 更高低点 | 上涨集中度 | 量能比 | 现价 / 目标 / 防守位 / 盈亏比 |\n");
            s.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (var c : o.pathA()) {
                PathA.Result r = c.result();
                s.append("| ").append(name(c.coin())).append(c.inPriorityRange() ? "" : " ⓘ").append(" | ").append(usd(c.coin().marketCap()))
                        .append(" | ").append(usd(c.coin().fdv())).append(" | ").append(pct(r.rLong())).append(" | ").append(pct(r.rRecent()))
                        .append(" | ").append(pct(r.r30())).append(" | ").append(pct(r.r90())).append(" | ").append(pct(r.r180()))
                        .append(" | ").append(pct(r.r365())).append(" | ").append(r.a1Pass() ? "✓" : "✗").append(" | ")
                        .append(r.drop65to90() ? "✓" : "✗").append(" | ").append(mark(r.higherLow())).append(" | ")
                        .append(r.top3Concentration() == null ? "缺失" : String.format(Locale.ROOT, "%.0f%%", r.top3Concentration() * 100))
                        .append(" | ").append(r.volumeRatio() == null ? "缺失" : String.format(Locale.ROOT, "%.2f", r.volumeRatio()))
                        .append(" | ").append(RiskReward.forPriceStructure(c.price(), c.basePrice(), c.baseLabel(), c.low30())).append(" |\n");
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

        if (o.prelim() != null && !o.prelim().isEmpty()) {
            s.append("### 路径 B 初步情景推算（现在买入的预期收益与时间）\n\n");
            s.append("口径：年化收入 = 最近 91 天协议收入 × 365/91；估值倍数 = 同类协议“流通市值/年化收入”的 p25/中位数/p75；")
                    .append("只用年化收入 ≥100 万美元、倍数 0.5–300 的同类样本；估值倍数从当前值起 36 个月逐步趋向目标（保守只压缩不扩张）；")
                    .append("收入增速 保守 0、基准 = 两季度年化增速的一半（封顶 50%）、乐观 = 两季度年化增速（封顶 100%）；公链类不做；流通量按当前值，**未计解锁，偏乐观**。")
                    .append("这是研究层核实前的参考，不是预测或承诺。\n\n");
            s.append("| 代币 | 年化收入 | 当前市值/收入 | 同类倍数 p25/中位/p75（样本） | 情景 | 6月 | 12月 | 24月 | 36月 | 达到 3/5/10 倍 |\n|---|---|---|---|---|---|---|---|---|---|\n");
            for (var pv : o.prelim().values()) {
                for (var sc : pv.scenarios()) {
                    s.append("| ").append(pv.coinId()).append(" | ").append(usd(pv.annualRevenue())).append(" | ")
                            .append(String.format(Locale.ROOT, "%.1f", pv.currentMultiple())).append(" | ")
                            .append(String.format(Locale.ROOT, "%.1f/%.1f/%.1f（%s %d 个）", pv.peerMultiples().get("p25"),
                                    pv.peerMultiples().get("median"), pv.peerMultiples().get("p75"), pv.peerGroup(), pv.peerCount()))
                            .append(" | ").append(sc.label()).append(String.format(Locale.ROOT, "（增速 %.0f%%，%.1f 倍）", sc.growth() * 100, sc.multiple()));
                    sc.multipleAt().values().forEach(v -> s.append(" | ").append(String.format(Locale.ROOT, "%.2fx", v)));
                    s.append(" | ").append(goals(sc)).append(" |\n");
                }
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

    static String goals(com.phoenixscout.research.ScenarioCalculator.Scenario sc) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        sc.monthsTo().forEach((goal, m) -> parts.add(goal + "倍" + (m == null ? "36月内不到" : "约" + m + "月")));
        return String.join("，", parts);
    }

    public static String prelimLineForTest(com.phoenixscout.scan.PrelimValuation.Result pv) {
        return prelimLine(pv);
    }

    static String prelimLine(com.phoenixscout.scan.PrelimValuation.Result pv) {
        StringBuilder s = new StringBuilder(RiskReward.forScenarios(pv.price(), pv.scenarios())).append("\n  现在买入（初步）：");
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (var sc : pv.scenarios()) {
            parts.add(sc.label() + " 12月" + String.format(Locale.ROOT, "%.1fx", sc.multipleAt().get(12))
                    + " 36月" + String.format(Locale.ROOT, "%.1fx", sc.multipleAt().get(36))
                    + "（5倍" + (sc.monthsTo().get(5) == null ? "36月内不到" : "约" + sc.monthsTo().get(5) + "月") + "）");
        }
        s.append(String.join("｜", parts));
        s.append(String.format(Locale.ROOT, "；当前市值/年化收入 %.1f 倍，同类中位 %.1f 倍", pv.currentMultiple(), pv.peerMultiples().get("median")));
        if (pv.currentMultiple() < pv.peerMultiples().get("p25") / 3) s.append("；⚠️ 远低于同类，收入可能并不归代币持有人，需研究核实");
        return s.toString();
    }

    public String telegramSummary(DailyScanService.Outcome o) {
        var b = o.benchmarks();
        var q = o.quality();
        StringBuilder s = new StringBuilder();
        s.append("🔥 日报 ").append(o.scanDay()).append("\n");
        s.append(o.aGrades().isEmpty() ? "A 级：无（候选均为 B 级待研究）\n"
                : "当前 A 级：" + String.join("、", o.aGrades()) + "（理由见单独推送）\n");
        s.append("基准 30天/90天：BTC ").append(pct(b.btc30())).append("/").append(pct(b.btc90()))
                .append("｜ETH ").append(pct(b.eth30())).append("/").append(pct(b.eth90()))
                .append("｜山寨中位数 ").append(pct(b.benchB30())).append("/").append(pct(b.benchB90())).append("\n");
        s.append("候选：超跌转强 ").append(o.pathA().size()).append(" 个，收入连增 ").append(o.pathB().size())
                .append(" 个，新币 ").append(o.newPool().size()).append(" 个\n");

        List<com.phoenixscout.scan.Pick> picks = o.picks() == null ? List.of() : o.picks();
        s.append("\n📌 今日推荐（盈亏比最高 ").append(picks.size()).append(" 个）");
        if (picks.isEmpty()) s.append("\n今日没有盈亏比合格的候选");
        for (int i = 0; i < picks.size(); i++) {
            var k = picks.get(i);
            s.append("\n").append(i + 1).append(". ").append(k.name()).append(" [").append(k.path().equals("A") ? "超跌转强" : "收入连增").append("]")
                    .append("\n   现价 ").append(RiskReward.price(k.price()))
                    .append("｜第一目标 ").append(RiskReward.price(k.takeProfit())).append("（").append(String.format(Locale.ROOT, "%+.0f%%", k.upside() * 100)).append("）")
                    .append(k.longTarget() == null ? "" : "｜长期目标 " + RiskReward.price(k.longTarget())
                            + String.format(Locale.ROOT, "（%+.0f%%）", (k.longTarget() / k.price() - 1) * 100))
                    .append("｜防守 ").append(RiskReward.price(k.stopLoss())).append("（").append(String.format(Locale.ROOT, "%+.0f%%", -k.downside() * 100)).append("）")
                    .append(String.format(Locale.ROOT, "｜盈亏比 %.1f", k.ratio()))
                    .append("\n   ").append(k.reason());
        }
        if (!picks.isEmpty()) {
            s.append("\n（目标/防守为情景或价格结构推算，不是预测或承诺；评级与买入由你决定）");
        }

        if (o.simSummary() != null) {
            var sm = o.simSummary();
            s.append("\n\n🧪 模拟组合（只记账，不下真实订单）");
            int shown = 0;
            for (var e : o.simEvents()) {
                if (!com.phoenixscout.sim.SimLedger.PORTFOLIO.equals(e.book())) continue;
                shown++;
                s.append("\n· ").append(e.kind()).append(" ").append(e.name()).append(" ").append(RiskReward.price(e.price()));
                if (e.pnlUsd() != null) s.append(String.format(Locale.ROOT, "，盈亏 %+.0f 美元（%+.1f%%）", e.pnlUsd(), e.pnlPct() * 100));
                if (e.note() != null) s.append("，").append(e.note());
            }
            if (shown == 0) s.append("\n· 今日无变动");
            s.append(String.format(Locale.ROOT, "\n权益 %.0f 美元（%+.1f%%），持仓 %d 个｜已平仓 %d 笔（盈 %d / 亏 %d）",
                    sm.equity() + sm.unrealized(), (sm.equity() + sm.unrealized()) / com.phoenixscout.sim.SimLedger.START_EQUITY * 100 - 100,
                    sm.open(), sm.closed(), sm.wins(), sm.losses()));
            s.append("\n实时盈亏：http://127.0.0.1:8100/");
        }

        s.append("\n\n数据：行情 ").append(q.marketCoins()).append("/").append(q.universeTarget()).append("，精确日线 ")
                .append(q.trackedWithLatest()).append("/").append(q.tracked());
        if (!q.problems().isEmpty()) s.append("\n⚠️ ").append(String.join("；", q.problems().stream().limit(3).toList()));
        s.append("\n完整报告：data\\reports\\").append(o.scanDay()).append(".md｜台账明细：data\\sim\\ledger.csv");
        return s.toString();
    }
}
