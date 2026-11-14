-- Local development databases for Backend B (PostgreSQL 18, port 5432).
-- Run as the postgres superuser:
--   & "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -h localhost -p 5432 -f scripts/local/create-databases.sql
-- Local-only passwords. Never reuse these outside your machine.

CREATE ROLE core_app LOGIN PASSWORD 'core_local';
CREATE DATABASE core_db OWNER core_app ENCODING 'UTF8' TEMPLATE template0;
REVOKE CONNECT ON DATABASE core_db FROM PUBLIC;

CREATE ROLE finance_app LOGIN PASSWORD 'finance_local';
CREATE DATABASE finance_db OWNER finance_app ENCODING 'UTF8' TEMPLATE template0;
REVOKE CONNECT ON DATABASE finance_db FROM PUBLIC;

-- Each service user can reach only its own database.
