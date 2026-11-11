-- ============================================================================
-- 32_add_password_reset_tokens.sql - password reset support for LOCAL users.
--
-- Adds nullable password_reset_token and password_reset_expires_at columns to
-- the users table so the forgot-password / reset-password flow can issue
-- single-use, time-bound reset tokens. Tokens are invalidated on use.
--
-- Idempotent: safe to re-run on an existing database.
-- ============================================================================

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS password_reset_token text;

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS password_reset_expires_at timestamptz;

COMMENT ON COLUMN public.users.password_reset_token IS
    'Single-use password reset token for LOCAL auth_provider accounts.';
COMMENT ON COLUMN public.users.password_reset_expires_at IS
    'Expiry timestamp for password_reset_token (default 1 hour after issue).';
