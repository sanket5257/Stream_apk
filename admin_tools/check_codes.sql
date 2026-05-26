-- ============================================
-- CHECK IF CODES EXIST IN THE TABLE
-- ============================================
-- Run this to see if you have any invite codes

-- Step 1: Count total codes
SELECT COUNT(*) as total_codes FROM invite_codes;

-- Step 2: Show all codes (if any exist)
SELECT 
    code,
    used,
    used_by,
    created_at
FROM invite_codes
ORDER BY created_at DESC;

-- Step 3: Check for specific test code
SELECT 
    CASE 
        WHEN EXISTS (SELECT 1 FROM invite_codes WHERE code = 'TEST-2024-001')
        THEN '✅ TEST-2024-001 EXISTS in database'
        ELSE '❌ TEST-2024-001 NOT FOUND - Need to insert codes!'
    END as status;
