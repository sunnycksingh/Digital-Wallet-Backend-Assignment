CREATE INDEX idx_ledger_account_time
  ON ledger_entries (account_id, created_at DESC, ledger_entry_id DESC);
CREATE INDEX idx_ledger_transfer ON ledger_entries (transfer_id);
CREATE INDEX IF NOT EXISTS idx_audit_transfer
  ON audit_logs (transfer_id);

CREATE FUNCTION block_ledger_changes() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_no_update_delete
  BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION block_ledger_changes();

CREATE UNIQUE INDEX IF NOT EXISTS uq_one_reversal_per_transfer
  ON transfer (original_transfer_id) WHERE original_transfer_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_issues_report ON reconciliation_issues (report_id);