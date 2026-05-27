-- ============================================================
-- StreamForge — Secure Auth Migration (LAUNCH BLOCKER)
-- Run this ENTIRE script in Supabase: Dashboard -> SQL Editor ->
-- New query -> paste -> Run.
--
-- WHAT THIS FIXES
--   1. Passwords were stored in PLAINTEXT.            -> bcrypt-hashed via pgcrypto.
--   2. RLS allowed the public anon key to SELECT every user row
--      (incl. passwords) and UPDATE any row.          -> anon loses ALL direct
--      table access; auth runs through SECURITY DEFINER functions only.
--
-- The SQL Editor runs as a privileged role, so it can create functions and
-- hash existing rows even though the anon app key no longer can.
--
-- SAFE TO RE-RUN: password hashing skips already-hashed rows; functions use
-- CREATE OR REPLACE; grants/revokes are idempotent.
-- ============================================================

-- 0. bcrypt support.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 1. Hash any existing plaintext passwords. bcrypt hashes start with "$2",
--    so this only touches rows that haven't been hashed yet.
UPDATE users
SET password = crypt(password, gen_salt('bf'))
WHERE password IS NOT NULL
  AND password NOT LIKE '$2%';

-- 2. Lock down the tables. With SECURITY DEFINER functions doing all the work,
--    the anon/authenticated roles need NO direct table privileges.
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Allow read access to users"     ON users;
DROP POLICY IF EXISTS "Allow insert new users"         ON users;
DROP POLICY IF EXISTS "Allow update device binding"    ON users;
REVOKE ALL ON users FROM anon, authenticated;

ALTER TABLE invite_codes ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Allow read access to invite codes" ON invite_codes;
DROP POLICY IF EXISTS "Allow update invite codes"         ON invite_codes;
REVOKE ALL ON invite_codes FROM anon, authenticated;

-- A small helper to build the "safe" user payload (NEVER includes password).
CREATE OR REPLACE FUNCTION _safe_user_json(u users)
RETURNS json
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT json_build_object(
        'id',               u.id,
        'email',            u.email,
        'username',         u.username,
        'is_active',        u.is_active,
        'active_device_id', u.active_device_id,
        'device_name',      u.device_name,
        'device_model',     u.device_model,
        'last_login',       u.last_login,
        'created_at',       u.created_at
    );
$$;

-- 3a. SIGN UP — validates invite code, enforces uniqueness, hashes the password,
--     creates the user, and marks the invite used, all in one transaction.
CREATE OR REPLACE FUNCTION app_signup(
    p_email        text,
    p_username     text,
    p_password     text,
    p_invite_code  text,
    p_device_id    text,
    p_device_name  text,
    p_device_model text
) RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_code invite_codes%ROWTYPE;
    v_user users%ROWTYPE;
BEGIN
    IF p_email IS NULL OR length(trim(p_email)) = 0
       OR p_username IS NULL OR length(trim(p_username)) = 0
       OR p_password IS NULL OR length(p_password) < 6 THEN
        RETURN json_build_object('status', 'error',
            'message', 'Email, username and a 6+ character password are required');
    END IF;

    -- Case-insensitive exact match on the invite code; lock the row to prevent
    -- two simultaneous signups from consuming the same code.
    SELECT * INTO v_code FROM invite_codes
        WHERE lower(code) = lower(trim(p_invite_code))
        FOR UPDATE;
    IF NOT FOUND THEN
        RETURN json_build_object('status', 'error', 'message', 'Invalid invite code');
    END IF;
    IF v_code.used THEN
        RETURN json_build_object('status', 'error', 'message', 'This invite code has already been used');
    END IF;

    IF EXISTS (SELECT 1 FROM users WHERE email = p_email) THEN
        RETURN json_build_object('status', 'error', 'message', 'Email already registered');
    END IF;
    IF EXISTS (SELECT 1 FROM users WHERE username = p_username) THEN
        RETURN json_build_object('status', 'error', 'message', 'Username already taken');
    END IF;

    INSERT INTO users (email, username, password, is_active,
                       active_device_id, device_name, device_model, last_login)
    VALUES (p_email, p_username, crypt(p_password, gen_salt('bf')), true,
            p_device_id, p_device_name, p_device_model, now())
    RETURNING * INTO v_user;

    UPDATE invite_codes SET used = true, used_by = v_user.id WHERE id = v_code.id;

    RETURN json_build_object('status', 'ok', 'user', _safe_user_json(v_user));
END;
$$;

-- 3b. LOGIN — verifies the bcrypt password and enforces single-device binding.
CREATE OR REPLACE FUNCTION app_login(
    p_username     text,
    p_password     text,
    p_device_id    text,
    p_device_name  text,
    p_device_model text
) RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user users%ROWTYPE;
BEGIN
    SELECT * INTO v_user FROM users WHERE username = p_username;

    -- Constant-ish failure for both "no such user" and "wrong password".
    IF NOT FOUND OR v_user.password IS NULL
       OR v_user.password <> crypt(p_password, v_user.password) THEN
        RETURN json_build_object('status', 'error', 'message', 'Invalid username or password');
    END IF;

    IF NOT v_user.is_active THEN
        RETURN json_build_object('status', 'error', 'message', 'Account has been deactivated');
    END IF;

    IF v_user.active_device_id IS NOT NULL AND v_user.active_device_id <> p_device_id THEN
        RETURN json_build_object('status', 'error',
            'message', 'This account is already active on another device.' || chr(10)
                       || 'Device: ' || coalesce(v_user.device_name, 'Unknown'));
    END IF;

    UPDATE users
       SET active_device_id = p_device_id,
           device_name      = p_device_name,
           device_model     = p_device_model,
           last_login       = now()
     WHERE id = v_user.id
     RETURNING * INTO v_user;

    RETURN json_build_object('status', 'ok', 'user', _safe_user_json(v_user));
END;
$$;

-- 3c. VALIDATE SESSION — periodic check that the account is still active and
--     bound to this device.
CREATE OR REPLACE FUNCTION app_validate_session(
    p_user_id   text,
    p_username  text,
    p_device_id text
) RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user users%ROWTYPE;
BEGIN
    SELECT * INTO v_user FROM users
        WHERE id::text = p_user_id AND username = p_username;
    IF NOT FOUND THEN
        RETURN json_build_object('status', 'error', 'message', 'Session expired. Please login again.');
    END IF;
    IF NOT v_user.is_active THEN
        RETURN json_build_object('status', 'error', 'message', 'Account has been deactivated');
    END IF;
    IF v_user.active_device_id IS DISTINCT FROM p_device_id THEN
        RETURN json_build_object('status', 'error', 'message', 'This device is no longer authorized');
    END IF;
    RETURN json_build_object('status', 'ok');
END;
$$;

-- 3d. LOGOUT — clears the device binding, but only for the caller's own device.
CREATE OR REPLACE FUNCTION app_logout(
    p_user_id   text,
    p_device_id text
) RETURNS json
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    UPDATE users
       SET active_device_id = NULL, device_name = NULL, device_model = NULL
     WHERE id::text = p_user_id AND active_device_id = p_device_id;
    RETURN json_build_object('status', 'ok');
END;
$$;

-- 4. Only allow these functions to be called by the app roles. EXECUTE on a
--    SECURITY DEFINER function is the ONLY thing the anon key can now do.
REVOKE ALL ON FUNCTION app_signup(text,text,text,text,text,text,text)  FROM public;
REVOKE ALL ON FUNCTION app_login(text,text,text,text,text)             FROM public;
REVOKE ALL ON FUNCTION app_validate_session(text,text,text)            FROM public;
REVOKE ALL ON FUNCTION app_logout(text,text)                           FROM public;

GRANT EXECUTE ON FUNCTION app_signup(text,text,text,text,text,text,text) TO anon, authenticated;
GRANT EXECUTE ON FUNCTION app_login(text,text,text,text,text)            TO anon, authenticated;
GRANT EXECUTE ON FUNCTION app_validate_session(text,text,text)           TO anon, authenticated;
GRANT EXECUTE ON FUNCTION app_logout(text,text)                          TO anon, authenticated;

-- 5. Verify. Expect: all password values start with "$2", and the anon role
--    has NO table privileges on users/invite_codes.
SELECT 'passwords hashed' AS check,
       count(*) FILTER (WHERE password LIKE '$2%') AS hashed,
       count(*) FILTER (WHERE password NOT LIKE '$2%') AS plaintext_remaining
FROM users;

SELECT 'anon table grants (should be empty)' AS check, table_name, privilege_type
FROM information_schema.role_table_grants
WHERE grantee = 'anon' AND table_name IN ('users', 'invite_codes');
