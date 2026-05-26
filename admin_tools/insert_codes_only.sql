-- ============================================
-- INSERT INVITE CODES
-- ============================================
-- The table exists, now we just need to add codes
-- Run this in Supabase SQL Editor

-- Insert 10 test codes
INSERT INTO invite_codes (code, used) VALUES 
    ('TEST-2024-001', FALSE),
    ('TEST-2024-002', FALSE),
    ('TEST-2024-003', FALSE),
    ('TEST-2024-004', FALSE),
    ('TEST-2024-005', FALSE),
    ('WELCOME-BETA', FALSE),
    ('STREAM-VIP-99', FALSE),
    ('EARLY-ACCESS', FALSE),
    ('BETA-TESTER', FALSE),
    ('INVITE-ALPHA', FALSE)
ON CONFLICT (code) DO NOTHING;

-- Verify codes were inserted
SELECT 
    '✅ Codes inserted successfully!' as status,
    COUNT(*) as total_codes
FROM invite_codes;

-- Show all available codes
SELECT 
    code,
    used,
    created_at
FROM invite_codes
WHERE used = FALSE
ORDER BY created_at DESC;
