
ALTER TABLE transfer
  ADD COLUMN IF NOT EXISTS from_account_id BIGINT REFERENCES accounts(account_id),
  ADD COLUMN IF NOT EXISTS to_account_id   BIGINT REFERENCES accounts(account_id);


ALTER TABLE risk_flags DROP CONSTRAINT IF EXISTS risk_flags_status_check;
ALTER TABLE risk_flags ADD CONSTRAINT risk_flags_status_check
  CHECK (status IN ('OPEN','RELEASED','DISMISSED','REVERSED'));


INSERT INTO fraud_rules (rule_name, description, rule_type, threshold_value, configuration, action) VALUES
  ('HIGH_AMOUNT_HOLD',
   'Hold any send or withdrawal of Rs 50,000 or more (threshold is in paise)',
   'HIGH_AMOUNT', 5000000, NULL, 'HOLD'),
  ('VELOCITY_FLAG',
   'Flag a wallet that already made 10 or more sends or withdrawals in the last 10 minutes',
   'VELOCITY', 10, '{"windowMinutes":10}', 'FLAG')
ON CONFLICT (rule_name) DO NOTHING;


CREATE INDEX IF NOT EXISTS idx_risk_flags_status ON risk_flags (status, risk_flag_id DESC);

CREATE INDEX IF NOT EXISTS idx_transfer_held ON transfer (transfer_id) WHERE status = 'HELD';
