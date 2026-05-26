-- ============================================
-- Invite Code System Migration
-- ============================================
-- This script creates the invite_codes table for the invite-code-based signup system.
-- Only users with valid invite codes can create accounts.

-- Create invite_codes table
CREATE TABLE IF NOT EXISTS invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create index on code for faster lookups
CREATE INDEX IF NOT EXISTS idx_invite_codes_code ON invite_codes(code);

-- Create index on used status for filtering
CREATE INDEX IF NOT EXISTS idx_invite_codes_used ON invite_codes(used);

-- Add comment to table
COMMENT ON TABLE invite_codes IS 'Stores invite codes for user registration. Each code can only be used once.';

-- Add comments to columns
COMMENT ON COLUMN invite_codes.id IS 'Unique identifier for the invite code';
COMMENT ON COLUMN invite_codes.code IS 'The actual invite code string (must be unique)';
COMMENT ON COLUMN invite_codes.used IS 'Whether this code has been used for signup';
COMMENT ON COLUMN invite_codes.used_by IS 'User ID who used this code (NULL if not used)';
COMMENT ON COLUMN invite_codes.created_at IS 'When this invite code was created';

-- ============================================
-- Sample Data (Optional - for testing)
-- ============================================
-- Uncomment the following lines to insert sample invite codes for testing

-- INSERT INTO invite_codes (code) VALUES 
--     ('WELCOME2024'),
--     ('BETA-ACCESS-001'),
--     ('STREAMFORGE-VIP'),
--     ('EARLY-BIRD-123'),
--     ('INVITE-ALPHA-99');

-- ============================================
-- Verification Queries
-- ============================================
-- Run these queries to verify the table was created successfully

-- Check table structure
-- SELECT column_name, data_type, is_nullable, column_default
-- FROM information_schema.columns
-- WHERE table_name = 'invite_codes'
-- ORDER BY ordinal_position;

-- Check indexes
-- SELECT indexname, indexdef
-- FROM pg_indexes
-- WHERE tablename = 'invite_codes';

-- Count total invite codes
-- SELECT COUNT(*) as total_codes FROM invite_codes;

-- Count unused invite codes
-- SELECT COUNT(*) as unused_codes FROM invite_codes WHERE used = FALSE;

-- Count used invite codes
-- SELECT COUNT(*) as used_codes FROM invite_codes WHERE used = TRUE;
