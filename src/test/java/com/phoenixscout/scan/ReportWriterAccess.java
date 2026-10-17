package com.phoenixscout.scan;

/** 测试辅助：调用 ReportWriter 的包内方法。 */
final class ReportWriterAccess {
    private ReportWriterAccess() {
    }

    static String line(PrelimValuation.Result r) {
        return com.phoenixscout.report.ReportWriter.prelimLineForTest(r);
    }
}
