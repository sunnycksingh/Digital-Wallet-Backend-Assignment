INSERT INTO job_locks (job_name) VALUES ('reconciliation')
ON CONFLICT (job_name) DO NOTHING;

ALTER TABLE reconciliation_issues ADD COLUMN IF NOT EXISTS ledger_entry_id BIGINT;

ALTER TABLE reconciliation_issues DROP CONSTRAINT IF EXISTS reconciliation_issues_check_type_check;
ALTER TABLE reconciliation_issues DROP CONSTRAINT IF EXISTS chk_issue_reference;

ALTER TABLE reconciliation_issues ADD CONSTRAINT reconciliation_issues_check_type_check
  CHECK (check_type IN ('GLOBAL_BALANCE','TRANSFER_BALANCE','STORED_BALANCE',
                        'OVERDRAFT','ORPHAN_TRANSFER','ORPHAN_ENTRY'));

ALTER TABLE reconciliation_issues ADD CONSTRAINT chk_issue_reference CHECK (
     (check_type = 'GLOBAL_BALANCE')
  OR (check_type IN ('TRANSFER_BALANCE','ORPHAN_TRANSFER') AND transfer_id IS NOT NULL)
  OR (check_type IN ('STORED_BALANCE','OVERDRAFT')         AND account_id IS NOT NULL)
  OR (check_type = 'ORPHAN_ENTRY'                          AND ledger_entry_id IS NOT NULL)
);

