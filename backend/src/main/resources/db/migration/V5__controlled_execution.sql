CREATE TABLE execution (
    operation_id uuid PRIMARY KEY,
    task_id uuid NOT NULL UNIQUE REFERENCES task(id),
    plan_id uuid NOT NULL UNIQUE REFERENCES plan(id),
    params_hash varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('EXECUTING','UNKNOWN','VERIFYING','SUCCEEDED','REJECTED','MANUAL')),
    checks integer NOT NULL DEFAULT 0 CHECK (checks >= 0),
    result jsonb,
    last_error varchar(80),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline_at timestamptz NOT NULL DEFAULT (clock_timestamp()+interval '5 minutes'),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX execution_ready_idx ON task(next_run_at,id)
    WHERE state IN ('APPROVED','EXECUTING','UNKNOWN','VERIFYING');
CREATE FUNCTION protect_execution_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'execution intent cannot be deleted' USING ERRCODE='23514';
    END IF;
    IF NEW.operation_id<>OLD.operation_id OR NEW.task_id<>OLD.task_id OR NEW.plan_id<>OLD.plan_id
       OR NEW.params_hash<>OLD.params_hash OR NEW.created_at<>OLD.created_at OR NEW.deadline_at<>OLD.deadline_at THEN
        RAISE EXCEPTION 'execution identity is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER fixed_execution_identity BEFORE UPDATE OR DELETE ON execution
    FOR EACH ROW EXECUTE FUNCTION protect_execution_identity();
