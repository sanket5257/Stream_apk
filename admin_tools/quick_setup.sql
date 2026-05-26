-- ============================================
-- QUICK SETUP - Run this first!
-- ============================================
-- Copy and paste this entire script into Supabase SQL Editor
-- This will create the table and insert test codes

-- Step 1: Create the invite_codes table
CREATE TABLE IF NOT EXISTS invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Step 2: Create indexes for performance
CREATE INDEX IF NOT EXISTS idx_invite_codes_code ON invite_codes(code);
CREATE INDEX IF NOT EXISTS idx_invite_codes_used ON invite_codes(used);

-- Step 3: Insert test invite codes (ready to use immediately!)
INSERT INTO invite_codes (code) VALUES 
    ('TEST-2024-001'),
    ('TEST-2024-002'),
    ('TEST-2024-003'),
    ('TEST-2024-004'),
    ('TEST-2024-005'),
    ('WELCOME-BETA'),
    ('STREAM-VIP-99'),
    ('EARLY-ACCESS'),
    ('BETA-TESTER'),
    ('INVITE-ALPHA')
ON CONFLICT (code) DO NOTHING;

-- Step 4: Verify setup
SELECT 
    'Setup Complete!' as status,
    COUNT(*) as total_codes_created,
    COUNT(*) FILTER (WHERE used = FALSE) as available_codes,
    COUNT(*) FILTER (WHERE used = TRUE) as used_codes
FROM invite_codes;

-- Step 5: Show all available codes
SELECT 
    code,
    'AVAILABLE' as status,
    created_at
FROM invite_codes
WHERE used = FALSE
ORDER BY created_at DESC;
