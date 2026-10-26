CREATE TABLE simulation_scenarios (
  scenario varchar(40) PRIMARY KEY,
  active_order_id varchar(100),
  fault varchar(40) NOT NULL DEFAULT 'NONE' CHECK (fault IN ('NONE','DELAY_RESPONSE_ONCE','QUERY_UNAVAILABLE'))
);
CREATE TABLE orders (
  id varchar(100) PRIMARY KEY,
  scenario varchar(40) NOT NULL REFERENCES simulation_scenarios(scenario),
  status varchar(30) NOT NULL CHECK (status IN ('PENDING_PAYMENT','PAID','CLOSED')),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency varchar(3) NOT NULL CHECK (currency = 'CNY'),
  payment_id varchar(100) NOT NULL UNIQUE,
  version bigint NOT NULL CHECK (version > 0)
);
CREATE INDEX orders_scenario ON orders(scenario);
CREATE TABLE payments (
  id varchar(100) PRIMARY KEY,
  order_id varchar(100) NOT NULL REFERENCES orders(id),
  status varchar(30) NOT NULL CHECK (status IN ('SUCCESS','PENDING','FAILED')),
  amount_minor bigint NOT NULL CHECK (amount_minor > 0),
  currency varchar(3) NOT NULL CHECK (currency = 'CNY'),
  paid_at timestamptz
);
CREATE TABLE callbacks (
  id varchar(100) PRIMARY KEY,
  payment_id varchar(100) NOT NULL REFERENCES payments(id),
  received_at timestamptz,
  result varchar(30) NOT NULL CHECK (result IN ('FAILED','PENDING','SUCCEEDED')),
  error_code varchar(80)
);
CREATE INDEX callbacks_payment ON callbacks(payment_id);
CREATE TABLE operations (
  operation_id varchar(100) PRIMARY KEY,
  order_id varchar(100) NOT NULL,
  payment_id varchar(100) NOT NULL,
  amount_minor bigint NOT NULL,
  currency varchar(3) NOT NULL,
  expected_version bigint NOT NULL,
  action varchar(60) NOT NULL,
  status varchar(30) NOT NULL CHECK (status IN ('IN_PROGRESS','SUCCEEDED','REJECTED')),
  code varchar(80) NOT NULL,
  before_version bigint NOT NULL DEFAULT 0,
  after_version bigint NOT NULL DEFAULT 0,
  completed_at timestamptz
);
CREATE INDEX operations_order ON operations(order_id);
