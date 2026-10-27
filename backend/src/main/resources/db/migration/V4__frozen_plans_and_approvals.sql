CREATE TABLE plan (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES task(id),
    version bigint NOT NULL CHECK (version > 0),
    action varchar(80) NOT NULL CHECK (action = 'VERIFY_PAYMENT_AND_ADVANCE_ORDER'),
    canonical_params text NOT NULL CHECK (jsonb_typeof(canonical_params::jsonb) = 'object'),
    params_hash varchar(64) NOT NULL CHECK (params_hash ~ '^[0-9a-f]{64}$'),
    evidence_id uuid NOT NULL REFERENCES evidence(id),
    source varchar(40) NOT NULL CHECK (source = 'TEST_STUB'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT (clock_timestamp() + interval '15 minutes'),
    UNIQUE(task_id, version),
    CHECK (expires_at > created_at)
);
CREATE INDEX plan_expiry_idx ON plan(expires_at, task_id);
CREATE TABLE approval (
    id uuid PRIMARY KEY,
    plan_id uuid NOT NULL UNIQUE REFERENCES plan(id),
    decision varchar(20) NOT NULL CHECK (decision IN ('APPROVE', 'REJECT')),
    approver_id varchar(80) NOT NULL,
    reason varchar(1000),
    decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (decision <> 'REJECT' OR length(btrim(reason)) > 0 AND reason IS NOT NULL)
);
CREATE FUNCTION reject_frozen_record_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'frozen record cannot be changed' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER frozen_plan BEFORE UPDATE OR DELETE ON plan
    FOR EACH ROW EXECUTE FUNCTION reject_frozen_record_change();
CREATE TRIGGER final_approval BEFORE UPDATE OR DELETE ON approval
    FOR EACH ROW EXECUTE FUNCTION reject_frozen_record_change();
-- T07 的等待状态没有真实方案；只恢复尚未审批的旧测试任务。
UPDATE task SET state='DIAGNOSING', version=version+1, next_run_at=clock_timestamp(),
    updated_at=clock_timestamp(), lease_owner=NULL, lease_until=NULL
    WHERE state='WAITING_APPROVAL';
