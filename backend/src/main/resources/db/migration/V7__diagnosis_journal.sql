ALTER TABLE plan DROP CONSTRAINT plan_source_check;
ALTER TABLE plan ADD CONSTRAINT plan_source_check CHECK(source IN ('TEST_STUB','AGENT_VALIDATED'));
CREATE TABLE diagnosis_run (
    task_id uuid PRIMARY KEY REFERENCES task(id),
    rounds integer NOT NULL DEFAULT 0,
    tools integer NOT NULL DEFAULT 0,
    reviews integer NOT NULL DEFAULT 0,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline_at timestamptz NOT NULL,
    result jsonb
);
CREATE TABLE diagnosis_event (
    id bigserial PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES task(id),
    epoch bigint NOT NULL,
    type varchar(40) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX diagnosis_event_history ON diagnosis_event(task_id,id);
CREATE TRIGGER immutable_diagnosis_event BEFORE UPDATE OR DELETE ON diagnosis_event
    FOR EACH ROW EXECUTE FUNCTION reject_frozen_record_change();
