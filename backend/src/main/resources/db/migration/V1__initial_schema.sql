-- =====================================================================
-- Retail Finance Core - baseline schema
--
-- Design notes
--   * Monetary columns are BIGINT. VND has no minor unit, so amounts are
--     whole dong; BIGINT keeps arithmetic exact with no floating-point drift.
--   * account.balance is a cached derived value guarded by `version` for
--     optimistic locking; the transaction ledger stays the source of truth.
--   * Every state-changing table carries created_at/updated_at so audit and
--     reconciliation windows can be bounded by time.
--   * Flyway owns this schema; Hibernate is set to `validate` and never
--     alters it.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Retail outlets: the physical points of sale transactions originate at.
-- ---------------------------------------------------------------------
CREATE TABLE outlet (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    code            VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    province_code   VARCHAR(16)  NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_outlet_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- App users. Auth is JWT + RBAC; passwords are BCrypt hashed.
-- `outlet_id` scopes a cashier to the outlet they physically work at.
-- ---------------------------------------------------------------------
CREATE TABLE app_user (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    full_name       VARCHAR(128) NOT NULL,
    role            VARCHAR(24)  NOT NULL,
    outlet_id       BIGINT       NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_username (username),
    KEY idx_app_user_outlet (outlet_id),
    CONSTRAINT fk_app_user_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT ck_app_user_role CHECK (role IN
        ('POS_STAFF', 'OUTLET_MANAGER', 'OPS', 'ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- Customer accounts.
-- `id_number_hash` is a SHA-256 of the national ID: the raw value is never
-- stored. `balance` is derived from the ledger and protected by `version`.
-- ---------------------------------------------------------------------
CREATE TABLE account (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    account_no      VARCHAR(32)  NOT NULL,
    customer_name   VARCHAR(128) NOT NULL,
    id_number_hash  CHAR(64)     NOT NULL,
    balance         BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_no (account_no),
    UNIQUE KEY uk_account_id_number_hash (id_number_hash),
    CONSTRAINT ck_account_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
-- ---------------------------------------------------------------------
-- Transactions: the immutable ledger.
-- Rows are appended and only move through their status lifecycle.
-- `idempotency_key` is the duplicate-submission guard: a POS terminal that
-- retries over a flaky link re-sends the same key and gets the original
-- transaction back instead of a second debit.
-- ---------------------------------------------------------------------
CREATE TABLE txn_transaction (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    txn_ref             VARCHAR(40)  NOT NULL,
    account_id          BIGINT       NOT NULL,
    outlet_id           BIGINT       NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    amount              BIGINT       NOT NULL,
    fee                 BIGINT       NOT NULL DEFAULT 0,
    status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    idempotency_key     VARCHAR(80)  NOT NULL,
    partner_ref         VARCHAR(64)  NULL,
    failure_reason      VARCHAR(255) NULL,
    settled_at          DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                            ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_txn_ref (txn_ref),
    UNIQUE KEY uk_txn_idempotency (idempotency_key),
    KEY idx_txn_account_created (account_id, created_at),
    KEY idx_txn_status_created (status, created_at),
    KEY idx_txn_partner_ref (partner_ref),
    CONSTRAINT fk_txn_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_txn_outlet FOREIGN KEY (outlet_id) REFERENCES outlet (id),
    CONSTRAINT ck_txn_type CHECK (type IN
        ('CASH_IN', 'CASH_OUT', 'TRANSFER_IN', 'TRANSFER_OUT', 'PAYMENT')),
    CONSTRAINT ck_txn_status CHECK (status IN ('PENDING', 'SETTLED', 'FAILED')),
    CONSTRAINT ck_txn_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_txn_fee_non_negative CHECK (fee >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------
-- Append-only audit trail. Kept independently of business rows so an
-- investigation can still reconstruct history after deletion.
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    actor           VARCHAR(64)  NOT NULL,
    actor_role      VARCHAR(24)  NULL,
    action          VARCHAR(48)  NOT NULL,
    target_type     VARCHAR(48)  NOT NULL,
    target_id       VARCHAR(64)  NOT NULL,
    detail          VARCHAR(512) NULL,
    occurred_at     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audit_actor_time (actor, occurred_at),
    KEY idx_audit_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;