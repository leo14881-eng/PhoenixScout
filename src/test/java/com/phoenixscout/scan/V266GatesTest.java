package com.phoenixscout.scan;

import com.phoenixscout.research.UnlockRisk;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** V2.6.6：7 天内大额解锁不推荐（SOSO 2026-10-24 解锁约 2,350 万枚，流通量约 3.43 亿）。 */
class V266GatesTest {

    static UnlockRisk.Result u(String date, Double amount) {
        return new UnlockRisk.Result(0.34, 79.0, 141.0, date, amount, null, List.of(), true);
    }

    @Test
    void bigUnlockWithinSevenDaysBlocks() {
        LocalDate day = LocalDate.of(2026, 10, 23);
        String r = DailyScanService.unlockSoon(u("2026-10-24", 2.35e7), 3.43e8, day);
        assertNotNull(r);
        assertTrue(r.contains("6.9%"), r);
        assertNull(DailyScanService.unlockSoon(u("2026-11-05", 2.35e7), 3.43e8, day), "超过 7 天不算");
        assertNull(DailyScanService.unlockSoon(u("2026-10-24", 1e6), 3.43e8, day), "不到流通量 1% 不算大额");
        assertNull(DailyScanService.unlockSoon(u(null, 2.35e7), 3.43e8, day), "日期缺失不判定");
        assertNull(DailyScanService.unlockSoon(u("2026-10-20", 2.35e7), 3.43e8, day), "已经过去的不算");
    }
}
