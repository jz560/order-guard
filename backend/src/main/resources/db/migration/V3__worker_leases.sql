ALTER TABLE task
  ADD COLUMN lease_owner varchar(80),
  ADD COLUMN lease_until timestamptz,
  ADD COLUMN lease_epoch bigint NOT NULL DEFAULT 0 CHECK (lease_epoch >= 0),
  ADD COLUMN next_run_at timestamptz DEFAULT clock_timestamp(),
  ADD COLUMN worker_failures integer NOT NULL DEFAULT 0 CHECK (worker_failures >= 0);
UPDATE task SET next_run_at=NULL WHERE state NOT IN ('QUEUED','DIAGNOSING');
ALTER TABLE task ADD CONSTRAINT task_lease_pair CHECK ((lease_owner IS NULL) = (lease_until IS NULL));
CREATE INDEX task_worker_due ON task(next_run_at,id)
  WHERE state IN ('QUEUED','DIAGNOSING') AND next_run_at IS NOT NULL;
CREATE TABLE task_step (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_id uuid NOT NULL REFERENCES task(id),
  seq bigint NOT NULL,
  type varchar(40) NOT NULL,
  status varchar(20) NOT NULL CHECK (status IN ('COMPLETED','RETRY','FAILED')),
  before_state varchar(30) NOT NULL,
  after_state varchar(30) NOT NULL,
  output varchar(300) NOT NULL,
  started_at timestamptz NOT NULL,
  finished_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(task_id,seq)
);
CREATE INDEX task_step_page ON task_step(task_id,id);
