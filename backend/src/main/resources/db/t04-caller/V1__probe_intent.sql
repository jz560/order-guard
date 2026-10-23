CREATE TABLE t04_probe.intent (
    operation_id text PRIMARY KEY,
    order_id text NOT NULL UNIQUE,
    payment_id text NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency text NOT NULL CHECK (currency = 'CNY'),
    expected_version bigint NOT NULL CHECK (expected_version > 0),
    action text NOT NULL CHECK (action = 'VERIFY_PAYMENT_AND_ADVANCE_ORDER'),
    status text NOT NULL CHECK (status IN ('PREPARED','IN_FLIGHT','UNKNOWN','SUCCEEDED','REJECTED')),
    last_error text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- 本实验始终保留订单限制；正式任务与限制释放策略在 T06/T10 实现。
