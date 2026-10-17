-- 研究层评级结果（代码按第十四条逐条校验后的结论）
CREATE TABLE research_grade (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    coin_id       TEXT NOT NULL,
    research_file TEXT NOT NULL,
    graded_at     TEXT NOT NULL,
    grade         TEXT NOT NULL,          -- A / B / C
    failed_items  TEXT NOT NULL,          -- JSON 数组
    detail        TEXT NOT NULL           -- JSON：逐项结果、情景推算
);
CREATE INDEX idx_research_grade_coin ON research_grade(coin_id, graded_at);
