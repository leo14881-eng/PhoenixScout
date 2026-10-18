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
import java.util.Map;

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

        s.append("## 今日推荐（V2.6）\n\n");
        s.append(banner(o)).append("\n\n");
        s.append("排序依据：期望值 = P(90 天内先到估值目标) × 目标涨幅 − P(先到防守价) × 防守跌幅，P 为历史回测的统计估计（样本 <30 个不输出）。")
                .append("估值目标 = 年化协议收入 × 同类市值/收入中位数 ÷ 未来 12 个月流通量（没有解锁计划时按总量，保守）；没有收入的项目“无法估值”，最高 B 级。")
                .append("防守价 = 现价 −35% 与近 90 天最低价中更低者。历史价格只作参考。\n\n");
        s.append("### 【A级】（最多 3 个）\n\n");
        if (o.aList().isEmpty()) s.append("无\n\n");
        for (int i = 0; i < o.aList().size(); i++) s.append(recBlock(i + 1, o.aList().get(i), true)).append("\n");
        s.append("### 【B级观察】（最多 5 个，按期望值排序；没有期望值时按估值空间；现价已高于估值目标的不列入）\n\n");
        if (o.bList().isEmpty()) s.append("无\n\n");
        for (int i = 0; i < o.bList().size(); i++) s.append(recBlock(i + 1, o.bList().get(i), true)).append("\n");
        if (o.excludedByQuality() > 0) s.append("另有 ").append(o.excludedByQuality()).append(" 个候选研究后质量分 ≤1，已排除。\n\n");
        s.append("### 【路径A观察】（回测没有证据表明有效，不进入推荐）\n\n");
        if (o.pathAWatch().isEmpty()) s.append("无\n\n");
        for (int i = 0; i < o.pathAWatch().size(); i++) s.append(watchLine(i + 1, o.pathAWatch().get(i))).append("\n");
        s.append("\n### 【今日淘汰】\n\n");
        if (o.eliminated().isEmpty()) s.append("无\n\n");
        for (var e : o.eliminated()) s.append("- ").append(e.name()).append("（昨日 ").append(e.grade()).append(" 级）：").append(e.reason()).append("\n");
        s.append("\n");
        s.append(backtestSection(o)).append("\n");
        if (o.simSummary() != null) {
            var sm = o.simSummary();
            s.append("## 模拟组合（只记账，不下真实订单）\n\n");
            s.append("规则（V2.6）：高倍币总预算 5,000 美元，最多 2 个，等额；只买 A 级或期望值 >0 的 B 级；首次建仓后，空出的位置只在季度复核（每季度第一次扫描）时补。")
                    .append("到估值目标卖一半并保本，剩余从最高收盘回撤 40% 卖出（无法估值的持仓翻倍后启用）；收盘跌破防守价止损；最长 3 年；")
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

        s.append("## 路径 A：长期严重超跌后转强（入选 ").append(o.pathATotalBeforeCap()).append(" 个")
                .append(o.pathATotalBeforeCap() > o.pathA().size() ? "，超过 30 个只保留相对强度前 20" : "").append("）\n\n");
        s.append("条件（V2.6）：30 天、90 天收益都 ≥ max(BTC, ETH, 基准 B) + 20 个百分点；长期跑输 BTC 和 ETH；没有更低的低点；")
                .append("最大单日涨幅 < 30 天涨幅的 50%；近 30 天日均成交额 ≥ 之前 90 天的 1.3 倍且 ≥ 200 万美元；站上 90 日均线且均线走平或向上。\n\n");
        if (o.pathA().isEmpty()) s.append("今日无。\n\n");
        else {
            s.append("| 币 | 市值 | FDV | 长期 | 30天（超额） | 90天（超额） | 180天 | 365天 | A1 | 最大单日占比 | 量能比 | 30天日均成交额 | 2025-10-31 价（仅参考） |\n");
            s.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (var c : o.pathA()) {
                PathA.Result r = c.result();
                s.append("| ").append(name(c.coin())).append(c.inPriorityRange() ? "" : " ⓘ").append(" | ").append(usd(c.coin().marketCap()))
                        .append(" | ").append(usd(c.coin().fdv())).append(" | ").append(pct(r.rLong()))
                        .append(" | ").append(pct(r.r30())).append("（").append(ppt(r.excess30())).append("）")
                        .append(" | ").append(pct(r.r90())).append("（").append(ppt(r.excess90())).append("）")
                        .append(" | ").append(pct(r.r180())).append(" | ").append(pct(r.r365())).append(" | ").append(r.a1Pass() ? "✓" : "✗")
                        .append(" | ").append(r.maxDayShare() == null ? "缺失" : String.format(Locale.ROOT, "%.0f%%", r.maxDayShare() * 100))
                        .append(" | ").append(r.volumeRatio() == null ? "缺失" : String.format(Locale.ROOT, "%.2f", r.volumeRatio()))
                        .append(" | ").append(usd(r.avgVolume30()))
                        .append(" | ").append(c.basePrice() == null ? "缺失" : RiskReward.price(c.basePrice())).append(" |\n");
            }
            s.append("\nⓘ = 市值不在优先区间（3,000 万–10 亿美元）。超额 = 比 BTC、ETH、基准 B 中最强者高出的百分点。量能比 = 近 30 天日均成交额 / 之前 90 天日均。成交额为 CoinGecko total_volume。\n\n");
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
        s.append(banner(o)).append("\n");
        s.append("基准 30天/90天：BTC ").append(pct(b.btc30())).append("/").append(pct(b.btc90()))
                .append("｜ETH ").append(pct(b.eth30())).append("/").append(pct(b.eth90()))
                .append("｜山寨中位数 ").append(pct(b.benchB30())).append("/").append(pct(b.benchB90())).append("\n");
        s.append("候选：收入连增 ").append(o.pathB().size()).append(" 个（推荐来源）｜超跌转强 ").append(o.pathATotalBeforeCap()).append(" 个（只观察）\n");

        s.append("\n【A级】");
        if (o.aList().isEmpty()) s.append("无");
        for (int i = 0; i < o.aList().size(); i++) s.append("\n").append(recBlock(i + 1, o.aList().get(i), false));
        s.append("\n\n【B级观察】");
        if (o.bList().isEmpty()) s.append("无");
        for (int i = 0; i < o.bList().size(); i++) s.append("\n").append(recBlock(i + 1, o.bList().get(i), false));
        s.append("\n\n【路径A观察】（信号未验证有效，不进入推荐）");
        if (o.pathAWatch().isEmpty()) s.append("无");
        for (int i = 0; i < o.pathAWatch().size(); i++) s.append("\n").append(watchLine(i + 1, o.pathAWatch().get(i)));
        s.append("\n\n【今日淘汰】");
        if (o.eliminated().isEmpty()) s.append("无");
        for (var e : o.eliminated()) s.append("\n· ").append(e.name()).append("：").append(e.reason());
        s.append("\n（估值、期望值为推算和统计估计，不是预测或承诺；买不买由你决定）");

        if (o.simSummary() != null) {
            var sm = o.simSummary();
            s.append("\n\n🧪 模拟组合（5,000 美元、最多 2 个，只记账）");
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
        s.append("\n完整报告：data\\reports\\").append(o.scanDay()).append(".md");
        return s.toString();
    }

    /** 顶部提示：回测没有证明有效时必须显示。 */
    static String banner(DailyScanService.Outcome o) {
        Map<String, Object> bt = o.backtest();
        boolean ok = bt != null && Boolean.TRUE.equals(bt.get("validated"));
        String verdict = bt == null || bt.get("verdict") == null ? "回测未完成" : String.valueOf(bt.get("verdict"));
        return ok ? "✅ 策略回测：" + verdict + "（" + bt.get("runDay") + "）" : "⚠️ 策略尚未验证有效，仅供观察（" + verdict + "）";
    }

    @SuppressWarnings("unchecked")
    static String backtestSection(DailyScanService.Outcome o) {
        Map<String, Object> bt = o.backtest();
        StringBuilder s = new StringBuilder("## 策略回测（每周日更新）\n\n");
        if (bt == null || bt.get("horizons") == null) return s.append("暂无回测结果。\n").toString();
        s.append("区间 ").append(bt.get("from")).append(" → ").append(bt.get("to")).append("（").append(bt.get("runDay")).append(" 运行），")
                .append("有入选的天数 ").append(bt.get("signalDays")).append("，样本 ").append(bt.get("episodes"))
                .append(" 个（同一币 30 天内重复入选只算一次），归零或数据中断 ")
                .append(String.format(Locale.ROOT, "%.1f%%", ((Number) bt.get("collapsedPct")).doubleValue())).append("。结论：").append(bt.get("verdict")).append("\n\n");
        s.append("| 入场后 | 样本 | 平均收益 | 胜率 | 相对 BTC | 相对 ETH | 相对山寨中位数 | 平均最大回撤 |\n|---|---|---|---|---|---|---|---|\n");
        for (Object x : (List<Object>) bt.get("horizons")) {
            Map<String, Object> h = (Map<String, Object>) x;
            s.append("| ").append(h.get("days")).append(" 天 | ").append(h.get("n")).append(" | ").append(pct(d(h.get("avgReturn"))))
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f%%", d(h.get("winRate")) * 100)).append(" | ")
                    .append(pct(d(h.get("excessBtc")))).append(" | ").append(pct(d(h.get("excessEth")))).append(" | ")
                    .append(pct(d(h.get("excessAlt")))).append(" | ").append(pct(d(h.get("avgMaxDrawdown")))).append(" |\n");
        }
        s.append("\n偏差：币池只含本地有日线的币（2026-10 归档时仍在前 2,500 名的币 + 每天跟踪的币），死币不在内，结果偏乐观。")
                .append("“质量分 ≥4 与其他”的对比没有历史质量分可用，从上线起在选币台账里累计（见每周复盘）。\n");
        return s.toString();
    }

    static Double d(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }

    static String ppt(Double v) {
        return v == null ? "缺失" : String.format(Locale.ROOT, "%+.0f 个百分点", v * 100).replace("-0 ", "0 ").replace("+0 ", "0 ");
    }

    /** 路径 A 观察：一行。 */
    static String watchLine(int i, DailyScanService.Rec r) {
        return i + ". " + r.name() + " 现价 " + RiskReward.price(r.price()) + "｜30天 " + pct(r.r30()) + "（超额 " + ppt(r.excess30())
                + "）｜90天 " + pct(r.r90()) + "（超额 " + ppt(r.excess90()) + "）｜质量分 " + r.quality().label();
    }

    /** 一个推荐币的展示块（日报与 Telegram 共用）。 */
    static String recBlock(int i, DailyScanService.Rec r, boolean md) {
        String nl = md ? "\n   - " : "\n   ";
        StringBuilder s = new StringBuilder();
        s.append(md ? i + ". **" + r.name() + "**" : i + ". " + r.name()).append(" [").append(r.path().equals("A") ? "超跌转强" : "收入连增").append("]");
        var u = r.unlock();
        s.append(nl).append("现价 ").append(RiskReward.price(r.price())).append("｜流通市值 ").append(usd(r.marketCap()))
                .append("｜FDV ").append(usd(r.fdv())).append("｜流通率 ")
                .append(u.circRatio() == null ? "缺失" : String.format(Locale.ROOT, "%.0f%%", u.circRatio() * 100));
        s.append(nl).append("30天 ").append(pct(r.r30())).append("（超额 ").append(ppt(r.excess30())).append("）｜90天 ")
                .append(pct(r.r90())).append("（超额 ").append(ppt(r.excess90())).append("）");
        var q = r.quality();
        s.append(nl).append("质量分 ").append(q.label());
        if (!q.missing().isEmpty()) s.append("，缺：").append(String.join("、", q.missing()));
        var t = r.target();
        if (t.available()) {
            s.append(nl).append("估值目标 ").append(RiskReward.price(t.targetPrice())).append(String.format(Locale.ROOT, "（%+.0f%%）", t.upside() * 100))
                    .append("：").append(t.method()).append("，按").append(t.supplyBasis());
        } else {
            s.append(nl).append("估值目标：无法估值（").append(t.reason()).append("）");
        }
        var ev = r.ev();
        s.append(nl).append("期望值 ").append(ev != null && ev.available() ? String.format(Locale.ROOT, "%+.0f%%（%s）", ev.ev() * 100, ev.note())
                : ev == null ? "缺失" : ev.note());
        s.append(nl).append("防守 ").append(RiskReward.price(r.stop())).append(String.format(Locale.ROOT, "（%+.0f%%）", -r.downside() * 100))
                .append("｜解锁：").append(u.summary());
        if (!r.risks().isEmpty()) s.append(nl).append("风险：").append(String.join("；", r.risks()));
        s.append(nl).append("理由：").append(r.reason());
        return s.toString();
    }
}
