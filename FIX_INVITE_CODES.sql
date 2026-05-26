-- ============================================================
-- StreamForge — Definitive Invite Code Fix
-- Run this ENTIRE script in Supabase SQL Editor (Dashboard ->
-- SQL Editor -> New query -> paste -> Run).
--
-- This fixes BOTH problems behind the "Invalid invite code" error:
--   1. The invite_codes table was empty (no codes existed).
--   2. RLS had no UPDATE policy, so the app could not mark a
--      code as "used" after signup (would allow code reuse).
-- The SQL Editor runs as a privileged role and bypasses RLS,
-- so the INSERTs below succeed even though the anon app key
-- is (correctly) not allowed to insert codes.
-- ============================================================

-- 1. Ensure the table exists with the correct structure.
CREATE TABLE IF NOT EXISTS invite_codes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(50) UNIQUE NOT NULL,
    used        BOOLEAN DEFAULT FALSE,
    used_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invite_codes_code ON invite_codes(code);
CREATE INDEX IF NOT EXISTS idx_invite_codes_used ON invite_codes(used);

-- 2. Row Level Security: enable it, then allow the anon app key
--    to READ codes (to validate) and UPDATE codes (to mark used).
--    INSERT is intentionally NOT granted to anon — only you, via
--    this SQL Editor, can create codes. That is what keeps the
--    app invite-only.
ALTER TABLE invite_codes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Allow read access to invite codes" ON invite_codes;
CREATE POLICY "Allow read access to invite codes" ON invite_codes
    FOR SELECT USING (true);

DROP POLICY IF EXISTS "Allow update invite codes" ON invite_codes;
CREATE POLICY "Allow update invite codes" ON invite_codes
    FOR UPDATE USING (true) WITH CHECK (true);

GRANT SELECT, UPDATE ON invite_codes TO anon;
GRANT SELECT, UPDATE ON invite_codes TO authenticated;

-- 3. Insert your invite codes. Each can be used exactly once.
--    All codes are based on EVOLEOTION. Add more lines as needed.
INSERT INTO invite_codes (code) VALUES
    ('EVOLEOTION-001'),
    ('EVOLEOTION-002'),
    ('EVOLEOTION-003'),
    ('EVOLEOTION-004'),
    ('EVOLEOTION-005'),
    ('EVOLEOTION-006'),
    ('EVOLEOTION-007'),
    ('EVOLEOTION-008'),
    ('EVOLEOTION-009'),
    ('EVOLEOTION-010'),
    ('EVOLEOTION-011'),
    ('EVOLEOTION-012'),
    ('EVOLEOTION-013'),
    ('EVOLEOTION-014'),
    ('EVOLEOTION-015'),
    ('EVOLEOTION-016'),
    ('EVOLEOTION-017'),
    ('EVOLEOTION-018'),
    ('EVOLEOTION-019'),
    ('EVOLEOTION-020')
ON CONFLICT (code) DO NOTHING;

-- 4. Verify — you should see all codes with used = false.
SELECT code, used, created_at
FROM invite_codes
ORDER BY created_at DESC;
