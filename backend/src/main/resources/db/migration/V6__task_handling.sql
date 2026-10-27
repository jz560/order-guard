CREATE TABLE task_handling (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES task(id),
    action varchar(30) NOT NULL CHECK(action IN ('CANCEL','TAKEOVER','RESOLVE')),
    expected_version bigint NOT NULL CHECK(expected_version>0),
    actor varchar(80) NOT NULL,
    reason varchar(1000) NOT NULL CHECK(length(btrim(reason))>0),
    resolution varchar(30),
    outcome varchar(80) NOT NULL,
    operation_id uuid REFERENCES execution(operation_id),
    evidence_id uuid REFERENCES evidence(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(task_id,action,expected_version)
);
CREATE TRIGGER immutable_handling BEFORE UPDATE OR DELETE ON task_handling
    FOR EACH ROW EXECUTE FUNCTION reject_frozen_record_change();
CREATE INDEX task_handling_history ON task_handling(task_id,created_at,id);
