package com.phoenixscout.report;

import com.phoenixscout.research.ScenarioCalculator;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RiskRewardTest {

    static ScenarioCalculator.Scenario sc(String name, double m12, double m36) {
        Map<Integer, Double> at = new LinkedHashMap<>();
        at.put(6, 1.0);
        at.put(12, m12);
        at.put(24, 1.0);
        at.put(36, m36);
        return new ScenarioCalculator.Scenario(name, name, 0, 0, at, Map.of());
    }

    @Test
    void scenarioLineShowsPriceTargetsAndRatio() {
        String s = RiskReward.forScenarios(2.0, List.of(sc("conservative", 0.8, 0.5), sc("base", 1.6, 3.0), sc("optimistic", 2.0, 6.0)));
        assertTrue(s.contains("现价 $2.000"), s);
        assertTrue(s.contains("12月 $3.200（+60%）"), s);
        assertTrue(s.contains("36月 $6.000（+200%）"), s);
        assertTrue(s.contains("12月盈亏比 3.0"), s);
        assertTrue(s.contains("36月盈亏比 4.0"), s);
    }

    @Test
    void noDownside() {
        String s = RiskReward.forScenarios(1.0, List.of(sc("conservative", 1.0, 1.0), sc("base", 1.5, 2.0)));
        assertTrue(s.contains("下行有限"), s);
    }

    @Test
    void priceStructure() {
        String s = RiskReward.forPriceStructure(1.0, 3.0, "2025-10-31 价", 0.8);
        assertTrue(s.contains("（+200%）"), s);
        assertTrue(s.contains("（-20%）"), s);
        assertTrue(s.contains("盈亏比 10.0"), s);
        assertEquals("现价缺失", RiskReward.forPriceStructure(null, 3.0, "x", 0.8));
    }
}
