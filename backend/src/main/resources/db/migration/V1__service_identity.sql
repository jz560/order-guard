-- T01 仅建立数据库归属标记；业务表随对应任务增加。
CREATE TABLE service_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    service_name VARCHAR(32) NOT NULL CHECK (service_name = 'backend'),
    initialized_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO service_identity (service_name) VALUES ('backend');
