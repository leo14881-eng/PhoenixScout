package com.phoenixscout.research;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioCalculatorTest {

    static ScenarioCalculator.Inputs in() {
        // 价格 1，流通 1 亿 → 流通市值 1 亿；年收入 1000 万（10 倍）；36 个月流通量增加到 1.2 亿
        return new ScenarioCalculator.Inputs(1.0, 1e8, 2e8, Map.of(12, 1.1e8, 36, 1.2e8), 1e7,
                Map.of("conservative", 0.0, "base", 0.5, "optimistic", 1.0), Map.of("p25", 5.0, "median", 10.0, "p75", 20.0));
    }

    @Test
    void supplyInterpolates() {
        assertEquals(1e8, ScenarioCalculator.supplyAt(in(), 0));
        assertEquals(1.05e8, ScenarioCalculator.supplyAt(in(), 6), 1);
        assertEquals(1.15e8, ScenarioCalculator.supplyAt(in(), 24), 1);
        assertEquals(1.2e8, ScenarioCalculator.supplyAt(in(), 48), 1);
    }

    @Test
    void scenariosAndGoals() {
        var s = ScenarioCalculator.scenarios(in());
        assertEquals(3, s.size());
        var cons = s.get(0);
        // 当前倍数 10，保守目标 min(10,5)=5；12 个月倍数 10-5/3=8.33，收入不变：1e7*8.333/1.1e8 = 0.7576
        assertEquals(0.7576, cons.multipleAt().get(12), 1e-3);
        assertNull(cons.monthsTo().get(3), "保守情景 36 个月内达不到 3 倍");
        var opt = s.get(2);
        // 乐观：36 个月收入 8e7 × 20（倍数已到目标）= 16 亿 / 1.2 亿 = 13.3x
        assertEquals(13.33, opt.multipleAt().get(36), 0.01);
        assertNotNull(opt.monthsTo().get(10));
        assertFalse(ScenarioCalculator.fiveXPlausible(s), "只有乐观情景到 5 倍不算合理的 5 倍情景（V2.6.5）");
        // 当前只有 5 倍收入、同类中位 15 倍：基准情景 36 个月内可到 5 倍
        var cheap = new ScenarioCalculator.Inputs(1.0, 1e8, 2e8, Map.of(12, 1.1e8, 36, 1.2e8), 2e7,
                Map.of("conservative", 0.0, "base", 0.5, "optimistic", 1.0), Map.of("p25", 8.0, "median", 15.0, "p75", 25.0));
        assertTrue(ScenarioCalculator.fiveXPlausible(ScenarioCalculator.scenarios(cheap)));
    }

    @Test
    void targetsUseSupplyAt36AndMedianMultiple() {
        var t = ScenarioCalculator.targets(in());
        var five = t.stream().filter(x -> x.times() == 5).findFirst().orElseThrow();
        assertEquals(5.0, five.targetPrice());
        assertEquals(6e8, five.targetCircMcap(), 1);
        assertEquals(1e9, five.targetFdv(), 1);
        assertEquals(6e7, five.requiredRevenue(), 1);
    }
}
