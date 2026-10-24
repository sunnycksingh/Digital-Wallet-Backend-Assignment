# Database schema

## A. Purpose and scope

This document describes the current PostgreSQL schema implemented by the wallet backend. It reflects the actual SQL in the repository files (`createquery.sql`, `fraud_rules.sql`, `IndexTriggers.sql`, `reconciliation_support.sql`) and the Java repository code that reads and updates these tables.

The schema is intentionally explicit, operational, and accounting-oriented rather than JPA-generated.

## B. Noun list

| Business concept | Database table | Purpose |
|---|---|---|
| User | `users` | Customer identity and KYC status |
| Wallet | `accounts` (`account_type='USER_WALLET'`) | User-owned balance ledger account |
| System account | `accounts` (`account_type='SYSTEM'`) | Operational balances for cash-in, cash-out, fee revenue |
| Financial transfer | `transfer` | One funds movement event |
| Ledger entry | `ledger_entries` | Double-entry debit/credit row |
| Idempotency record | `idempotency_keys` | Prevents duplicate request execution |
| Audit event | `audit_logs` | Captures transfer status changes |
| Fraud rule | `fraud_rules` | Declares amount and velocity checks |
| Risk flag | `risk_flags` | Record of rule hit on a transfer |
| Reconciliation run | `reconciliation_runs` | A run of the daily/periodic audit job |
| Reconciliation issue | `reconciliation_issues` | A detected discrepancy |
| Background lock | `job_locks` | Prevents overlapping reconciliation jobs |

## C. Table reference

### `users`
Purpose: tenant/customer identity.

- Primary key: `user_id` (`BIGINT GENERATED ALWAYS AS IDENTITY`)
- Important columns:
  - `username` (`VARCHAR(100) NOT NULL`)
  - `email` (`VARCHAR(255) NOT NULL UNIQUE`)
  - `phone` (`VARCHAR(20) NULL`)
  - `kyc_status` (`VARCHAR(20) NOT NULL DEFAULT 'PENDING'`)
- Constraints:
  - `CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED'))`
- Indexes: none explicitly created in the schema file; the `email` uniqueness provides a built-in index.

### `accounts`
Purpose: stores both user wallets and system accounts.

- Primary key: `account_id`
- Important columns:
  - `user_id` (`BIGINT REFERENCES users(user_id)`) — null for system accounts
  - `account_type` (`VARCHAR(20) NOT NULL`) — `USER_WALLET` or `SYSTEM`
  - `wallet_type` (`VARCHAR(20) NULL`) — `MAIN` or `SAVING`
  - `system_account_code` (`VARCHAR(50) NULL`) — `CASH_IN`, `CASH_OUT`, `FEE_REVENUE`
  - `balance_paise` (`BIGINT NOT NULL DEFAULT 0`)
  - `currency` (`VARCHAR(3) NOT NULL DEFAULT 'INR'`) with `CHECK (currency = 'INR')`
- Constraints:
  - `CHECK (account_type IN ('USER_WALLET','SYSTEM'))`
  - `CHECK (wallet_type IN ('MAIN','SAVING'))`
  - `CHECK (system_account_code IN ('CASH_IN','CASH_OUT','FEE_REVENUE'))`
  - `CHECK ((account_type = 'USER_WALLET' AND user_id IS NOT NULL AND wallet_type IS NOT NULL AND system_account_code IS NULL) OR (account_type = 'SYSTEM' AND user_id IS NULL AND wallet_type IS NULL AND system_account_code IS NOT NULL))`
  - `CHECK (account_type = 'SYSTEM' OR balance_paise >= 0)`
  - `UNIQUE (user_id, wallet_type)`
  - `UNIQUE (system_account_code)`
- Nullable columns:
  - `user_id` is nullable only for system accounts.
  - `wallet_type` is nullable only for system accounts.
  - `system_account_code` is nullable only for user wallets.

### `transfer`
Purpose: one financial event; may be a top-up, peer transfer, withdrawal, fee, or reversal.

- Primary key: `transfer_id`
- Important columns:
  - `initiated_by_user_id` (`BIGINT REFERENCES users(user_id)`) — the user who initiated the transfer when applicable
  - `transfer_type` (`VARCHAR(30) NOT NULL`) — `TOP_UP`, `PEER_TRANSFER`, `WITHDRAWAL`, `FEE`, `REVERSAL`
  - `status` (`VARCHAR(20) NOT NULL`) — `PENDING`, `COMPLETED`, `HELD`, `FAILED`
  - `amount_paise` (`BIGINT NOT NULL`) and `CHECK (amount_paise > 0)`
  - `original_transfer_id` (`BIGINT REFERENCES transfer(transfer_id)`) — self-reference for reversal records
- Constraints:
  - `CHECK (transfer_type IN ('TOP_UP','PEER_TRANSFER','WITHDRAWAL','FEE','REVERSAL'))`
  - `CHECK (status IN ('PENDING','COMPLETED','HELD','FAILED'))`
  - `CHECK ((transfer_type = 'REVERSAL' AND original_transfer_id IS NOT NULL) OR (transfer_type <> 'REVERSAL' AND original_transfer_id IS NULL))`
- Self-referential relationship: `transfer.original_transfer_id -> transfer.transfer_id`.
- Typical use: a reversal row points back to the original transfer it reversed.

### `ledger_entries`
Purpose: double-entry accounting records.

- Primary key: `ledger_entry_id`
- Important columns:
  - `transfer_id` (`BIGINT NOT NULL REFERENCES transfer(transfer_id)`) — owning transfer
  - `account_id` (`BIGINT NOT NULL REFERENCES accounts(account_id)`) — affected wallet/system account
  - `direction` (`VARCHAR(10) NOT NULL`) — `DEBIT` or `CREDIT`
  - `amount_paise` (`BIGINT NOT NULL`) with `CHECK (amount_paise > 0)`
- Constraints:
  - `CHECK (direction IN ('DEBIT','CREDIT'))`
- Indexes:
  - `idx_ledger_account_time` on `(account_id, created_at)`
  - `idx_ledger_transfer` on `(transfer_id)`
- Notes: there is no direct `UNIQUE` constraint on transfer/account/direction pair; the application enforces ledger balance logic in code.

### `idempotency_keys`
Purpose: prevents replay or duplication of the same user-level request.

- Primary key: `idempotency_id`
- Important columns:
  - `user_id` (`BIGINT NOT NULL REFERENCES users(user_id)`) — lock scope for a user
  - `idempotency_key` (`UUID NOT NULL`)
  - `request_hash` (`VARCHAR(64) NOT NULL`)
  - `status` (`VARCHAR(20) NOT NULL DEFAULT 'PROCESSING'`) — `PROCESSING` or `COMPLETED`
  - `response_code` (`INTEGER NULL`)
  - `response_body` (`TEXT NULL`)
- Constraints:
  - `CHECK (request_hash ~ '^[0-9a-fA-F]{64}$')`
  - `CHECK (status IN ('PROCESSING', 'COMPLETED'))`
  - `CHECK (response_code BETWEEN 100 AND 599)`
  - `UNIQUE (user_id, idempotency_key)`
  - `CHECK ((status <> 'COMPLETED') OR (response_code IS NOT NULL AND response_body IS NOT NULL))`
- Important behavior: the application stores the original request hash and final response text so exact replays return identical payloads.

### `audit_logs`
Purpose: immutable history of transfer state transitions.

- Primary key: `audit_id`
- Important columns:
  - `transfer_id` (`BIGINT NOT NULL REFERENCES transfer(transfer_id)`) — subject transfer
  - `old_status` (`VARCHAR(20) NULL`)
  - `new_status` (`VARCHAR(20) NOT NULL`)
  - `changed_by_user_id` (`BIGINT REFERENCES users(user_id)`) — human actor when applicable
  - `changed_by_type` (`VARCHAR(20) NOT NULL`) — `USER`, `ADMIN`, `SYSTEM`
  - `reason` (`TEXT NULL`)
- Constraints:
  - `CHECK (new_status IN ('PENDING','COMPLETED','HELD','FAILED'))`
  - `CHECK (changed_by_type IN ('USER', 'ADMIN', 'SYSTEM'))`
  - `CHECK (old_status IS NULL OR old_status IN ('PENDING','COMPLETED','HELD','FAILED'))`
  - `CHECK ((changed_by_type IN ('USER','ADMIN') AND changed_by_user_id IS NOT NULL) OR (changed_by_type = 'SYSTEM' AND changed_by_user_id IS NULL))`
- Index: `idx_audit_transfer` on `audit_logs(transfer_id)`

### `fraud_rules`
Purpose: declarative rule set used by fraud checks.

- Primary key: `rule_id`
- Important columns:
  - `rule_name` (`VARCHAR(100) NOT NULL UNIQUE`)
  - `description` (`TEXT NULL`)
  - `rule_type` (`VARCHAR(50) NOT NULL`)
  - `threshold_value` (`BIGINT NULL`)
  - `configuration` (`JSONB NULL`)
  - `action` (`VARCHAR(20) NOT NULL`) — `HOLD` or `FLAG`
- Constraints:
  - `CHECK (threshold_value IS NULL OR threshold_value >= 0)`
  - `CHECK (action IN ('HOLD', 'FLAG'))`

### `risk_flags`
Purpose: a transfer-level fraud outcome record.

- Primary key: `risk_flag_id`
- Important columns:
  - `transfer_id` (`BIGINT NOT NULL REFERENCES transfer(transfer_id)`) — the flagged transfer
  - `rule_id` (`BIGINT NOT NULL REFERENCES fraud_rules(rule_id)`) — the matched rule
  - `status` (`VARCHAR(20) NOT NULL DEFAULT 'OPEN'`) — `OPEN`, `RELEASED`, `DISMISSED`
  - `llm_label` (`VARCHAR(20) NULL`) — `likely_ok`, `review`, `likely_fraud`
  - `llm_explanation` (`TEXT NULL`)
- Constraints:
  - `CHECK (status IN ('OPEN', 'RELEASED', 'DISMISSED'))`
  - `CHECK (llm_label IN ('likely_ok', 'review', 'likely_fraud'))`
  - `UNIQUE (transfer_id, rule_id)`
- Index: `idx_risk_flags_status` on `(status, risk_flag_id DESC)`

### `reconciliation_runs`
Purpose: a single reconciliation execution job.

- Primary key: `report_id`
- Important columns:
  - `started_at` (`TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`) 
  - `ended_at` (`TIMESTAMPTZ NULL`)
  - `status` (`VARCHAR(20) NOT NULL DEFAULT 'RUNNING'`) — `RUNNING`, `COMPLETED`, `FAILED`
  - `problem_count` (`BIGINT NOT NULL DEFAULT 0`) 
- Constraints:
  - `CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))`
  - `CHECK (problem_count >= 0)`
  - `CHECK (status <> 'COMPLETED' OR ended_at IS NOT NULL)`

### `reconciliation_issues`
Purpose: captures one discrepancy found during a reconciliation run.

- Primary key: `issue_id`
- Important columns:
  - `report_id` (`BIGINT NOT NULL REFERENCES reconciliation_runs(report_id)`) — parent run
  - `check_type` (`VARCHAR(50) NOT NULL`) — `TRANSFER_BALANCE` or `STORED_BALANCE`
  - `transfer_id` (`BIGINT NULL REFERENCES transfer(transfer_id)`) — transfer involved when relevant
  - `account_id` (`BIGINT NULL REFERENCES accounts(account_id)`) — account involved when relevant
  - `ledger_entry_id` (`BIGINT NULL`) — optional linked ledger entry
  - `details` (`TEXT NULL`)
- Constraints:
  - `CHECK (check_type IN ('TRANSFER_BALANCE', 'STORED_BALANCE'))`
  - `CHECK ((check_type = 'TRANSFER_BALANCE' AND transfer_id IS NOT NULL) OR (check_type = 'STORED_BALANCE' AND account_id IS NOT NULL))`
- Index: `idx_issues_report` on `(report_id)`

### `job_locks`
Purpose: serializes reconciliation runs.

- Primary key: `job_lock_id`
- Important columns:
  - `job_name` (`VARCHAR(100) NOT NULL UNIQUE`)
  - `locked_at` (`TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()`)
- Behavior: the service inserts a row and then uses `SELECT ... FOR UPDATE NOWAIT` to ensure only one reconciliation job runs at a time.

## D. Cardinality and relationships

### 1. `users` to `accounts`
- Relationship: one-to-many
- Parent: `users`
- Child: `accounts`
- Actual FK: `accounts.user_id -> users.user_id`
- Optional vs mandatory: the relationship is optional from the child side; a system account still has `user_id = NULL`.
- Example: user `#17` can have `MAIN` and `SAVING` user-wallet accounts.

### 2. `users` to `transfer`
- Relationship: one-to-many
- Parent: `users`
- Child: `transfer`
- Actual FK: `transfer.initiated_by_user_id -> users.user_id`
- Optional vs mandatory: optional because not every transfer is initiated by a user in the same way as system-generated events.
- Example: a user submits a peer transfer that creates a `PEER_TRANSFER` row referencing them as the initiator.

### 3. `accounts` to `ledger_entries`
- Relationship: one-to-many
- Parent: `accounts`
- Child: `ledger_entries`
- Actual FK: `ledger_entries.account_id -> accounts.account_id`
- Optional vs mandatory: mandatory in the child row; every ledger entry belongs to a specific account.
- Example: a ₹100 transfer debits wallet A and credits wallet B; two ledger entries are created, one for each account.

### 4. `transfer` to `ledger_entries`
- Relationship: one-to-many
- Parent: `transfer`
- Child: `ledger_entries`
- Actual FK: `ledger_entries.transfer_id -> transfer.transfer_id`
- Optional vs mandatory: mandatory on child side.
- Example: a single `PEER_TRANSFER` has both debit and credit ledger rows.

### 5. `users` to `idempotency_keys`
- Relationship: one-to-many
- Parent: `users`
- Child: `idempotency_keys`
- Actual FK: `idempotency_keys.user_id -> users.user_id`
- Optional vs mandatory: mandatory on child side.
- Example: repeated payment requests from user `#7` use the same UUID key and same user-scoped idempotency record.

### 6. `transfer` to `audit_logs`
- Relationship: one-to-many
- Parent: `transfer`
- Child: `audit_logs`
- Actual FK: `audit_logs.transfer_id -> transfer.transfer_id`
- Optional vs mandatory: mandatory on child side.
- Example: a transfer with status change from `PENDING` to `HELD` gets an audit row.

### 7. `fraud_rules` to `risk_flags`
- Relationship: one-to-many
- Parent: `fraud_rules`
- Child: `risk_flags`
- Actual FK: `risk_flags.rule_id -> fraud_rules.rule_id`
- Optional vs mandatory: mandatory on child side.
- Example: a high-amount rule hit produces a `risk_flags` row for the transfer.

### 8. `transfer` to `risk_flags`
- Relationship: one-to-many
- Parent: `transfer`
- Child: `risk_flags`
- Actual FK: `risk_flags.transfer_id -> transfer.transfer_id`
- Optional vs mandatory: mandatory on child side.
- Example: a transfer can have multiple risk hits from different rules.

### 9. `reconciliation_runs` to `reconciliation_issues`
- Relationship: one-to-many
- Parent: `reconciliation_runs`
- Child: `reconciliation_issues`
- Actual FK: `reconciliation_issues.report_id -> reconciliation_runs.report_id`
- Optional vs mandatory: mandatory on child side.
- Example: one run finds several mismatched ledger records and stores one issue row per discrepancy.

### 10. `transfer` self-reference for reversal
- Relationship: one-to-many, self-referential
- Parent: `transfer`
- Child: `transfer`
- Actual FK: `transfer.original_transfer_id -> transfer.transfer_id`
- Optional vs mandatory: optional on the child side; a non-reversal row has `original_transfer_id` null.
- Example: transfer `#33` is a reversal of transfer `#12`.

## E. ER diagram

```mermaid
erDiagram
  USERS ||--o{ ACCOUNTS : owns
  USERS ||--o{ TRANSFER : initiates
  USERS ||--o{ IDEMPOTENCY_KEYS : uses
  USERS ||--o{ AUDIT_LOGS : changed_by

  TRANSFER ||--o{ LEDGER_ENTRIES : has
  TRANSFER ||--o{ AUDIT_LOGS : logs
  TRANSFER ||--o{ RISK_FLAGS : triggers
  TRANSFER ||--o{ RECONCILIATION_ISSUES : may_appear
  TRANSFER ||--o{ TRANSFER : reverses

  ACCOUNTS ||--o{ LEDGER_ENTRIES : impacts

  FRAUD_RULES ||--o{ RISK_FLAGS : matches
  RECONCILIATION_RUNS ||--o{ RECONCILIATION_ISSUES : contains
  JOB_LOCKS ||..o RECONCILIATION_RUNS : serializes

  USERS {
    bigint user_id PK
    varchar username
    varchar email UK
    varchar phone
    varchar kyc_status
  }

  ACCOUNTS {
    bigint account_id PK
    bigint user_id FK
    varchar account_type
    varchar wallet_type
    varchar system_account_code
    bigint balance_paise
    varchar currency
  }

  TRANSFER {
    bigint transfer_id PK
    bigint initiated_by_user_id FK
    varchar transfer_type
    varchar status
    bigint amount_paise
    bigint original_transfer_id FK
  }

  LEDGER_ENTRIES {
    bigint ledger_entry_id PK
    bigint transfer_id FK
    bigint account_id FK
    varchar direction
    bigint amount_paise
  }

  IDEMPOTENCY_KEYS {
    bigint idempotency_id PK
    bigint user_id FK
    uuid idempotency_key
    varchar request_hash
    varchar status
    int response_code
    text response_body
  }

  AUDIT_LOGS {
    bigint audit_id PK
    bigint transfer_id FK
    varchar old_status
    varchar new_status
    bigint changed_by_user_id FK
    varchar changed_by_type
    text reason
  }

  FRAUD_RULES {
    bigint rule_id PK
    varchar rule_name UK
    text description
    varchar rule_type
    bigint threshold_value
    jsonb configuration
    varchar action
  }

  RISK_FLAGS {
    bigint risk_flag_id PK
    bigint transfer_id FK
    bigint rule_id FK
    varchar status
    varchar llm_label
    text llm_explanation
  }

  RECONCILIATION_RUNS {
    bigint report_id PK
    timestamptz started_at
    timestamptz ended_at
    varchar status
    bigint problem_count
  }

  RECONCILIATION_ISSUES {
    bigint issue_id PK
    bigint report_id FK
    varchar check_type
    bigint transfer_id FK
    bigint account_id FK
    bigint ledger_entry_id
    text details
  }

  JOB_LOCKS {
    bigint job_lock_id PK
    varchar job_name UK
    timestamptz locked_at
  }
```

## Notes

- The schema is implemented in PostgreSQL and uses `GENERATED ALWAYS AS IDENTITY` for surrogate keys.
- There is no ORM-generated schema file in the repo; these are the actual SQL definitions checked in by the assignment.
- The repo’s manual startup notes (`TransferDataFile.txt`) indicate Docker-based schema creation rather than Compose-managed startup.
