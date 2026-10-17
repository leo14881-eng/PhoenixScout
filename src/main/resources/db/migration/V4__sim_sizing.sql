-- 仓位按风险自定：记录每笔承担的风险金额、平仓时的 R 倍数（盈亏 ÷ 风险金额）
ALTER TABLE sim_position ADD COLUMN risk_usd REAL;
ALTER TABLE sim_position ADD COLUMN r_multiple REAL;
ALTER TABLE sim_position ADD COLUMN size_note TEXT;
