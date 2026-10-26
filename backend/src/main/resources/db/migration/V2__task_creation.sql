CREATE TABLE task (
  id uuid PRIMARY KEY,
  order_id varchar(100) NOT NULL,
  state varchar(30) NOT NULL CHECK (state IN ('QUEUED','DIAGNOSING','WAITING_APPROVAL','APPROVED','EXECUTING','UNKNOWN','VERIFYING','MANUAL','FINISHED')),
  outcome varchar(80),
  version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
  created_by varchar(80) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(id,order_id)
);
CREATE UNIQUE INDEX task_one_active_order ON task(order_id)
  WHERE state NOT IN ('FINISHED','MANUAL');
CREATE INDEX task_list ON task(created_at DESC,id DESC);
CREATE INDEX task_order_history ON task(order_id,created_at DESC);
CREATE TABLE order_guard (
  order_id varchar(100) PRIMARY KEY,
  task_id uuid NOT NULL UNIQUE,
  reason varchar(40) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(task_id,order_id) REFERENCES task(id,order_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE task_creation_request (
  actor varchar(80) NOT NULL,
  request_key varchar(100) NOT NULL,
  order_id varchar(100) NOT NULL,
  task_id uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(actor,request_key),
  FOREIGN KEY(task_id,order_id) REFERENCES task(id,order_id)
);
CREATE TABLE evidence (
  id uuid PRIMARY KEY,
  task_id uuid NOT NULL REFERENCES task(id),
  source varchar(40) NOT NULL,
  business_version bigint NOT NULL,
  payload jsonb NOT NULL,
  fetched_at timestamptz NOT NULL
);
CREATE INDEX evidence_task ON evidence(task_id,fetched_at,id);
CREATE TABLE audit_event (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id uuid NOT NULL REFERENCES task(id),
  actor varchar(80) NOT NULL,
  event_type varchar(40) NOT NULL,
  correlation_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX audit_task ON audit_event(task_id,id);
