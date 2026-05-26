-- ============================================
-- DELETE ALL CODES AND CREATE NEW ONES (NO HYPHENS)
-- ============================================
-- Run this in Supabase SQL Editor

-- Step 1: Delete all existing invite codes
DELETE FROM invite_codes;

-- Step 2: Insert new codes WITHOUT hyphens
INSERT INTO invite_codes (code, used) VALUES 
    ('TEST2024001', FALSE),
    ('TEST2024002', FALSE),
    ('TEST2024003', FALSE),
    ('TEST2024004', FALSE),
    ('TEST2024005', FALSE),
    ('WELCOMEBETA', FALSE),
    ('STREAMVIP99', FALSE),
    ('EARLYACCESS', FALSE),
    ('BETATESTER', FALSE),
    ('INVITEALPHA', FALSE),
    ('ALPHA001', FALSE),
    ('BETA001', FALSE),
    ('GAMMA001', FALSE),
    ('DELTA001', FALSE),
    ('OMEGA001', FALSE);

-- Step 3: Verify new codes were created
SELECT 
    '✅ Codes reset successfully!' as status,
    COUNT(*) as total_codes
FROM invite_codes;

-- Step 4: Show all available codes
SELECT 
    code,
    used,
    created_at
FROM invite_codes
WHERE used = FALSE
ORDER BY code;
