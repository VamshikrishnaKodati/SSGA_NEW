-- ============================================================================
-- 00_roles.sql — database roles, extensions and schema grants
--
-- Run this FIRST, as a superuser (or a role with CREATEROLE + CREATEDB),
-- against a fresh database before 01_schema.sql.
--
-- Idempotent: safe to re-run.
--
-- Roles
--   ssga_app       Login role used by the Spring Boot application (RLS applies).
--                  Password is NOT stored here; set it out of band, e.g.:
--                    ALTER ROLE ssga_app PASSWORD '<set-by-operator>';
--   ssga_workflow  NOLOGIN, BYPASSRLS role reserved for trusted internal
--                  workflows. The application does not log in as this role;
--                  SECURITY DEFINER functions run as their owner instead.
--
-- Extensions
--   pgcrypto       crypt()/gen_salt() for password hashing.
--   plpgsql        installed by default with PostgreSQL.
--
-- Requires PostgreSQL >= 15.
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssga_app') THEN
        CREATE ROLE ssga_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ssga_workflow') THEN
        CREATE ROLE ssga_workflow NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;
    END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO ssga_app', current_database());
END
$$;

-- Schema usage/grants are created by 01_schema.sql.
