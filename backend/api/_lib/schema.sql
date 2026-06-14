-- ─────────────────────────────────────────────────────────────
-- Схема БД для Scooter Rent API.
-- Применяется один раз через POST /api/init (защищённый endpoint).
-- ─────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    id              SERIAL PRIMARY KEY,
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'admin',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS scooters (
    id                  SERIAL PRIMARY KEY,
    user_id             INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL,
    documented_number   VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_scooters_user_id ON scooters(user_id);

CREATE TABLE IF NOT EXISTS renters (
    id                              SERIAL PRIMARY KEY,
    user_id                         INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                            VARCHAR(255) NOT NULL,
    phone_number                    VARCHAR(50)  NOT NULL,
    debt_amount                     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    rent_duration_days              INTEGER NOT NULL,
    rent_start_date_timestamp       BIGINT NOT NULL,
    is_returned                     BOOLEAN NOT NULL DEFAULT FALSE,
    is_overdue_sms_sent             BOOLEAN NOT NULL DEFAULT FALSE,
    scooter_id                      INTEGER REFERENCES scooters(id) ON DELETE SET NULL,
    scooter_name                    VARCHAR(255),
    last_payment_timestamp          BIGINT,
    balance                         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_renters_user_id   ON renters(user_id);
CREATE INDEX IF NOT EXISTS idx_renters_scooter_id ON renters(scooter_id);

CREATE TABLE IF NOT EXISTS contract_history (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    renter_id   INTEGER NOT NULL REFERENCES renters(id) ON DELETE CASCADE,
    timestamp   BIGINT NOT NULL,
    type        VARCHAR(50) NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_contract_history_user_id   ON contract_history(user_id);
CREATE INDEX IF NOT EXISTS idx_contract_history_renter_id ON contract_history(renter_id);
CREATE INDEX IF NOT EXISTS idx_contract_history_timestamp ON contract_history(timestamp DESC);

CREATE TABLE IF NOT EXISTS notification_history (
    id          SERIAL PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    timestamp   BIGINT NOT NULL,
    renter_id   INTEGER REFERENCES renters(id) ON DELETE SET NULL,
    title       VARCHAR(255) NOT NULL,
    message     TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notifications_user_id   ON notification_history(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_timestamp ON notification_history(timestamp DESC);
