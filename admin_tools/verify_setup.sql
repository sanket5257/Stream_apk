-- ============================================
-- VERIFY INVITE CODE SETUP
-- ============================================
-- Run this to check if everything is set up correctly

-- Step 1: Check if invite_codes table exists
SELECT 
    CASE 
        WHEN EXISTS (
            SELECT FROM information_schema.tables 
            WHERE table_name = 'invite_codes'
        ) 
        THEN '✅ Table EXISTS'
        ELSE '❌ Table DOES NOT EXIST - Run quick_setup.sql first!'
    END as table_status;

-- Step 2: Count total invite codes
SELECT 
    '📊 Total Codes' as metric,
    COUNT(*) as count
FROM invite_codes;

-- Step 3: Show all available codes
SELECT 
    '📋 Available Codes:' as info,
    code,
    used,
    created_at
FROM invite_codes
ORDER BY created_at DESC;

-- Step 4: Check for specific test code
SELECT 
    CASE 
        WHEN EXISTS (SELECT 1 FROM invite_codes WHERE code = 'TEST-2024-001')
        THEN '✅ TEST-2024-001 EXISTS'
        ELSE '❌ TEST-2024-001 NOT FOUND'
    END as test_code_status;

-- Step 5: Show table structure
SELECT 
    column_name,
    data_type,
    is_nullable
FROM information_schema.columns
WHERE table_name = 'invite_codes'
ORDER BY ordinal_position;
