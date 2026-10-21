#!/usr/bin/env bash
set -euo pipefail
# 由官方镜像在空数据目录中执行；密码以 psql 变量引用，不拼接 SQL。
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=backend_password="$BACKEND_DB_PASSWORD" \
  --set=simulator_password="$SIMULATOR_DB_PASSWORD" <<'SQL'
CREATE ROLE backend_app LOGIN PASSWORD :'backend_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE simulator_app LOGIN PASSWORD :'simulator_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE DATABASE agent_app OWNER backend_app;
CREATE DATABASE agent_simulator OWNER simulator_app;
REVOKE ALL ON DATABASE agent_app FROM PUBLIC;
REVOKE ALL ON DATABASE agent_simulator FROM PUBLIC;
GRANT CONNECT ON DATABASE agent_app TO backend_app;
GRANT CONNECT ON DATABASE agent_simulator TO simulator_app;
\connect agent_app
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO backend_app;
\connect agent_simulator
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO simulator_app;
SQL
