CREATE TABLE t04_probe.orders (
    order_id text PRIMARY KEY,
    status text NOT NULL CHECK (status IN ('PENDING_PAYMENT','PAID','CLOSED')),
    payment_id text NOT NULL UNIQUE,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency text NOT NULL CHECK (currency = 'CNY'),
    payment_status text NOT NULL CHECK (payment_status IN ('SUCCESS','PROCESSING','FAILED')),
    payment_amount_minor bigint NOT NULL CHECK (payment_amount_minor > 0),
    payment_currency text NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    effect_count bigint NOT NULL DEFAULT 0 CHECK (effect_count >= 0)
);
CREATE TABLE t04_probe.operations (
    operation_id text PRIMARY KEY,
    request_hash text NOT NULL CHECK (length(request_hash) = 64),
    request_json jsonb NOT NULL,
    status text NOT NULL CHECK (status IN ('IN_PROGRESS','SUCCEEDED','REJECTED')),
    code text NOT NULL,
    before_version bigint NOT NULL,
    after_version bigint NOT NULL,
    completed_at timestamptz
);
