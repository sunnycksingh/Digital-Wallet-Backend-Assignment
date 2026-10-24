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
