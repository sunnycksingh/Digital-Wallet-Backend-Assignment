CREATE TABLE users (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20),
    kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE accounts (
  account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id BIGINT REFERENCES users(user_id),
  account_type VARCHAR(20) NOT NULL
      CHECK (account_type IN ('USER_WALLET','SYSTEM')),
  wallet_type VARCHAR(20)
      CHECK (wallet_type IN ('MAIN','SAVING')),
  system_account_code VARCHAR(50)
      CHECK (system_account_code IN ('CASH_IN','CASH_OUT','FEE_REVENUE')),
  balance_paise BIGINT NOT NULL DEFAULT 0,
  currency VARCHAR(3) NOT NULL DEFAULT 'INR'
      CHECK (currency = 'INR'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT chk_account_type_fields CHECK (
    (account_type = 'USER_WALLET'
      AND user_id IS NOT NULL
      AND wallet_type IS NOT NULL
      AND system_account_code IS NULL)
    OR
    (account_type = 'SYSTEM'
      AND user_id IS NULL
      AND wallet_type IS NULL
      AND system_account_code IS NOT NULL)
  ),

  CONSTRAINT chk_no_overdraft CHECK (account_type = 'SYSTEM' OR balance_paise >= 0),
  CONSTRAINT unq_user_wallet_type UNIQUE (user_id, wallet_type),
  CONSTRAINT unq_system_account_code UNIQUE (system_account_code)
);

CREATE TABLE transfer(
	transfer_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	initiated_by_user_id BIGINT REFERENCES users(user_id),
	transfer_type VARCHAR(30) NOT NULL 
		CHECK (transfer_type IN ('TOP_UP','PEER_TRANSFER','WITHDRAWAL','FEE','REVERSAL')),
	status VARCHAR(20) NOT NULL
		CHECK (status IN ('PENDING','COMPLETED','HELD','FAILED')),
	amount_paise BIGINT NOT NULL
		CHECK (amount_paise > 0),
	original_transfer_id BIGINT REFERENCES transfer(transfer_id),
	note TEXT,
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
	
	  CONSTRAINT chk_reversal_reference CHECK (
        (transfer_type = 'REVERSAL' AND original_transfer_id IS NOT NULL)
        OR
        (transfer_type <> 'REVERSAL' AND original_transfer_id IS NULL)
    )

);


CREATE TABLE ledger_entries(
	ledger_entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	transfer_id BIGINT NOT NULL REFERENCES transfer(transfer_id),
	account_id BIGINT NOT NULL REFERENCES accounts(account_id),
	direction VARCHAR(10) NOT NULL
		CHECK (direction IN ('DEBIT','CREDIT')),
	amount_paise BIGINT NOT NULL
		CHECK (amount_paise > 0),
	created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP

);

CREATE TABLE idempotency_keys (
    idempotency_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id),
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL
        CHECK (request_hash ~ '^[0-9a-fA-F]{64}$'),
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING', 'COMPLETED')),
    response_code INTEGER
        CHECK (response_code BETWEEN 100 AND 599),
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_user_idempotency_key
        UNIQUE (user_id, idempotency_key),

    CONSTRAINT chk_idempotency_response CHECK (
        status <> 'COMPLETED'
        OR (response_code IS NOT NULL AND response_body IS NOT NULL)
    )
);

CREATE TABLE audit_logs (
    audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfer(transfer_id),
    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL
        CHECK (new_status IN (
            'PENDING',
            'COMPLETED',
            'HELD',
            'FAILED'
        )),
    changed_by_user_id BIGINT REFERENCES users(user_id),
    changed_by_type VARCHAR(20) NOT NULL
        CHECK (changed_by_type IN ('USER', 'ADMIN', 'SYSTEM')),
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_old_status CHECK (
        old_status IS NULL
        OR old_status IN ('PENDING', 'COMPLETED', 'HELD', 'FAILED')
    ),

    CONSTRAINT chk_audit_actor CHECK (
        (changed_by_type IN ('USER', 'ADMIN') AND changed_by_user_id IS NOT NULL)
        OR
        (changed_by_type = 'SYSTEM' AND changed_by_user_id IS NULL)
    )
);


CREATE TABLE fraud_rules (
    rule_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    rule_type VARCHAR(50) NOT NULL,
    threshold_value BIGINT
        CHECK (threshold_value IS NULL OR threshold_value >= 0),
    configuration JSONB,
    action VARCHAR(20) NOT NULL
        CHECK (action IN ('HOLD', 'FLAG')),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE risk_flags (
    risk_flag_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id BIGINT NOT NULL REFERENCES transfer(transfer_id),
    rule_id BIGINT NOT NULL REFERENCES fraud_rules(rule_id),
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RELEASED', 'DISMISSED')),
    llm_explanation TEXT,
    llm_label VARCHAR(20)
        CHECK (llm_label IN ('likely_ok', 'review', 'likely_fraud')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_transfer_rule UNIQUE (transfer_id, rule_id)
);

CREATE TABLE reconciliation_runs (
    report_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    problem_count BIGINT NOT NULL DEFAULT 0
        CHECK (problem_count >= 0),

    CONSTRAINT chk_reconciliation_times CHECK (
        ended_at IS NULL OR ended_at >= started_at
    ),

    CONSTRAINT chk_reconciliation_completion CHECK (
        status <> 'COMPLETED' OR ended_at IS NOT NULL
    )
);

CREATE TABLE reconciliation_issues (
    issue_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id BIGINT NOT NULL
        REFERENCES reconciliation_runs(report_id),
    check_type VARCHAR(50) NOT NULL
        CHECK (check_type IN ('TRANSFER_BALANCE', 'STORED_BALANCE')),
    transfer_id BIGINT REFERENCES transfer(transfer_id),
    account_id BIGINT REFERENCES accounts(account_id),
    expected_value BIGINT,
    actual_value BIGINT,
    description TEXT NOT NULL,

    CONSTRAINT chk_issue_reference CHECK (
        (check_type = 'TRANSFER_BALANCE'
            AND transfer_id IS NOT NULL)
        OR
        (check_type = 'STORED_BALANCE'
            AND account_id IS NOT NULL)
    )
);

CREATE TABLE job_locks (
    job_lock_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL UNIQUE
);
