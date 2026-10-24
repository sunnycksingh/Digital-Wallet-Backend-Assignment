# Transaction Isolation and Concurrency Controls

This document records only the isolation-related behavior that is directly visible in the repository code and SQL scripts. It excludes assumptions and optional design ideas.

## 1. Observed transaction isolation settings

The application explicitly sets database isolation levels in the service layer:

- `TransferService` uses `Connection.TRANSACTION_READ_COMMITTED`
- `ReconciliationService` uses `Connection.TRANSACTION_REPEATABLE_READ`

The actual code path is:

```java
conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
```

and:

```java
c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
```

These are the only transactional isolation settings found in the checked-in Java code.

## 2. What the code indicates

### 2.1 Transfer operations: READ COMMITTED

Normal wallet transactions run under `READ COMMITTED`.

This means:
- a transaction sees only committed data at the moment it reads
- dirty reads are avoided
- each statement sees a new committed snapshot
- account rows are still protected by explicit locking before balance-changing writes

This matches the transactional pattern used by wallet operations, where multiple account rows are locked before posting balanced ledger entries.

### 2.2 Reconciliation: REPEATABLE READ

The reconciliation job runs under `REPEATABLE READ` and explains the intent directly in code:

> "Everything happens in ONE REPEATABLE READ transaction: all 5 checks see the same snapshot, even if transfers keep happening while the job runs."

That guarantees that the reconciliation checks are taken from one stable snapshot of the database while the report is being built.

## 3. SQL-level isolation and consistency guards

The SQL files used in the project add database constraints and locks that control concurrency and protect the correctness of financial records.

### 3.1 Ledger is append-only

From `IndexTriggers.sql`:

```sql
CREATE FUNCTION block_ledger_changes() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'ledger_entries is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_no_update_delete
  BEFORE UPDATE OR DELETE ON ledger_entries
  FOR EACH ROW EXECUTE FUNCTION block_ledger_changes();
```

This is a strong data-integrity rule: once a ledger row is inserted, it cannot be modified or deleted. This prevents ledger tampering during concurrent processing and supports an audit-style accounting model.

### 3.2 One reversal per transfer

From `IndexTriggers.sql`:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_one_reversal_per_transfer
  ON transfer (original_transfer_id) WHERE original_transfer_id IS NOT NULL;
```

This prevents duplicate reversal rows for the same original transfer.

### 3.3 Reversal and transfer rules

From `createquery.sql`:

```sql
CONSTRAINT chk_reversal_reference CHECK (
    (transfer_type = 'REVERSAL' AND original_transfer_id IS NOT NULL)
    OR
    (transfer_type <> 'REVERSAL' AND original_transfer_id IS NULL)
)
```

This ensures reversal rows are explicitly linked to an original transfer and non-reversal rows are not misclassified.

### 3.4 Account-level safety checks

From `createquery.sql`:

```sql
CONSTRAINT chk_no_overdraft CHECK (account_type = 'SYSTEM' OR balance_paise >= 0),
CONSTRAINT unq_user_wallet_type UNIQUE (user_id, wallet_type),
CONSTRAINT unq_system_account_code UNIQUE (system_account_code)
```

These constraints prevent user wallets from going negative and enforce uniqueness of wallet-per-type and system-account identity.

### 3.5 Idempotency isolation by user and request hash

From `createquery.sql`:

```sql
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
```

This prevents duplicate execution of the same request key for the same user and preserves a stable response for repeated requests.

## 4. Reconciliation locking and job coordination

From `reconciliation_support.sql`:

```sql
INSERT INTO job_locks (job_name) VALUES ('reconciliation')
ON CONFLICT (job_name) DO NOTHING;
```

The reconciliation flow uses a one-row lock table to ensure that only one reconciliation job runs at a time.

From the Java reconciliation service:

```java
if (!repo.tryLock(c)) {
    c.rollback();
    throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_RUNNING",
            "A reconciliation run is already in progress");
}
```

This creates a single-run concurrency gate around the entire reconciliation job.

## 5. Fraud/risk isolation and state transitions

From `fraud_rules.sql`:

```sql
ALTER TABLE risk_flags DROP CONSTRAINT IF EXISTS risk_flags_status_check;
ALTER TABLE risk_flags ADD CONSTRAINT risk_flags_status_check
  CHECK (status IN ('OPEN','RELEASED','DISMISSED','REVERSED'));
```

This modifies risk state transitions and keeps them constrained to a known finite set.

Also:

```sql
CREATE INDEX IF NOT EXISTS idx_risk_flags_status ON risk_flags (status, risk_flag_id DESC);
```

This allows the application to efficiently query open or released risk reviews without scanning unrelated rows.

## 6. Summary of real isolation behavior in this repository

The repository implements isolation in two ways:

1. Transaction-level isolation in Java
   - `READ COMMITTED` for wallet transfer operations
   - `REPEATABLE READ` for reconciliation jobs

2. Database-level protection via SQL
   - append-only ledger rows
   - unique reversal constraint
   - account balance and wallet uniqueness constraints
   - idempotency uniqueness per user/key
   - single reconciliation lock using `job_locks`

There is no evidence in the repo of a broader multi-level isolation policy beyond these settings and constraints.
