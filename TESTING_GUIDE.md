# Invite Code System - Testing Guide

## ✅ Build & Installation Status

**Build**: ✅ Successful  
**Installation**: ✅ Installed on Redmi 8 (Android 10)  
**APK Location**: `Stream_apk/app/build/outputs/apk/debug/streamforge.apk`

---

## Prerequisites for Testing

### 1. Setup Database (Required First!)

Before testing the app, you MUST set up the database:

```sql
-- Step 1: Open Supabase Dashboard
-- Go to: https://supabase.com/dashboard
-- Navigate to: Your Project > SQL Editor

-- Step 2: Copy and paste this SQL:
CREATE TABLE IF NOT EXISTS invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_invite_codes_code ON invite_codes(code);
CREATE INDEX IF NOT EXISTS idx_invite_codes_used ON invite_codes(used);

-- Step 3: Insert test invite codes
INSERT INTO invite_codes (code) VALUES 
    ('TEST-2024-001'),
    ('TEST-2024-002'),
    ('TEST-2024-003'),
    ('WELCOME-BETA'),
    ('STREAM-VIP-99');

-- Step 4: Verify codes were created
SELECT * FROM invite_codes;
```

### 2. Generate Additional Codes (Optional)

```bash
# Install Python dependencies
pip install supabase python-dotenv

# Create .env file
cp admin_tools/.env.example admin_tools/.env

# Edit .env with your Supabase credentials
# Then generate codes:
python admin_tools/generate_invite_codes.py --count 10
```

---

## Testing Steps

### Test 1: Verify App Installation ✓

1. **Check app is installed**
   - Look for "StreamForge" app icon on your Redmi 8
   - App should be visible in app drawer

2. **Launch the app**
   - Tap the StreamForge icon
   - App should open to login/signup screen

**Expected Result**: App launches successfully

---

### Test 2: Test Signup with Valid Invite Code ✓

1. **Open the app** on your Redmi 8

2. **Navigate to Signup**
   - You should see "Welcome Back" screen
   - Tap "Don't have an account? Sign Up"

3. **Verify Invite Code Field Appears**
   - Screen should change to "Create Account"
   - You should see these fields:
     - Email
     - Username
     - Password
     - **Invite Code** (NEW!)

4. **Fill in the signup form**:
   ```
   Email: test1@example.com
   Username: testuser1
   Password: password123
   Invite Code: TEST-2024-001
   ```

5. **Tap "Sign Up" button**

6. **Expected Results**:
   - ✅ "Account created successfully!" toast message
   - ✅ Automatically logged in
   - ✅ Redirected to main screen
   - ✅ Device info shown at bottom

7. **Verify in Database**:
   ```sql
   -- Check the invite code was marked as used
   SELECT * FROM invite_codes WHERE code = 'TEST-2024-001';
   -- Should show: used = TRUE, used_by = [user_id]
   
   -- Check user was created
   SELECT * FROM users WHERE username = 'testuser1';
   -- Should show the new user
   ```

**Expected Result**: ✅ Account created successfully with valid invite code

---

### Test 3: Test Signup with Invalid Invite Code ✗

1. **Logout** (if logged in)
   - Go to app settings/menu
   - Tap "Logout"

2. **Navigate to Signup** again
   - Tap "Don't have an account? Sign Up"

3. **Fill in the form with INVALID code**:
   ```
   Email: test2@example.com
   Username: testuser2
   Password: password123
   Invite Code: INVALID-CODE-999
   ```

4. **Tap "Sign Up" button**

5. **Expected Results**:
   - ❌ Error toast: "Invalid invite code"
   - ❌ Account NOT created
   - ❌ Still on signup screen
   - ❌ Can try again with different code

6. **Verify in Database**:
   ```sql
   -- User should NOT exist
   SELECT * FROM users WHERE username = 'testuser2';
   -- Should return no rows
   ```

**Expected Result**: ❌ Signup fails with "Invalid invite code" error

---

### Test 4: Test Signup with Already Used Code ✗

1. **Stay on Signup screen** (or navigate back)

2. **Fill in the form with USED code**:
   ```
   Email: test3@example.com
   Username: testuser3
   Password: password123
   Invite Code: TEST-2024-001
   ```
   *(This code was already used in Test 2)*

3. **Tap "Sign Up" button**

4. **Expected Results**:
   - ❌ Error toast: "This invite code has already been used"
   - ❌ Account NOT created
   - ❌ Still on signup screen

5. **Verify in Database**:
   ```sql
   -- User should NOT exist
   SELECT * FROM users WHERE username = 'testuser3';
   -- Should return no rows
   
   -- Code should still be marked as used by first user
   SELECT * FROM invite_codes WHERE code = 'TEST-2024-001';
   -- Should show: used = TRUE, used_by = [testuser1's id]
   ```

**Expected Result**: ❌ Signup fails with "already been used" error

---

### Test 5: Test Signup with Empty Invite Code ✗

1. **Stay on Signup screen**

2. **Fill in the form WITHOUT invite code**:
   ```
   Email: test4@example.com
   Username: testuser4
   Password: password123
   Invite Code: [leave empty]
   ```

3. **Tap "Sign Up" button**

4. **Expected Results**:
   - ❌ Error shown: "Please enter an invite code"
   - ❌ Red error text under Invite Code field
   - ❌ Account NOT created

**Expected Result**: ❌ Validation error for empty invite code

---

### Test 6: Test Multiple Successful Signups ✓

1. **Create second account with different code**:
   ```
   Email: test5@example.com
   Username: testuser5
   Password: password123
   Invite Code: TEST-2024-002
   ```
   - Should succeed ✅

2. **Logout and create third account**:
   ```
   Email: test6@example.com
   Username: testuser6
   Password: password123
   Invite Code: TEST-2024-003
   ```
   - Should succeed ✅

3. **Verify in Database**:
   ```sql
   -- Check all three codes are now used
   SELECT code, used, used_by FROM invite_codes 
   WHERE code IN ('TEST-2024-001', 'TEST-2024-002', 'TEST-2024-003');
   -- All should show: used = TRUE
   
   -- Check all three users exist
   SELECT username, email FROM users 
   WHERE username IN ('testuser1', 'testuser5', 'testuser6');
   -- Should return 3 rows
   ```

**Expected Result**: ✅ Multiple accounts can be created with different valid codes

---

### Test 7: Test Login (No Invite Code Required) ✓

1. **Logout** if logged in

2. **On Login screen** (default screen)
   - Should see "Welcome Back"
   - Should see: Username, Password fields
   - Should NOT see: Email or Invite Code fields

3. **Login with existing account**:
   ```
   Username: testuser1
   Password: password123
   ```

4. **Tap "Login" button**

5. **Expected Results**:
   - ✅ "Login successful!" toast
   - ✅ Redirected to main screen
   - ✅ No invite code required for login

**Expected Result**: ✅ Login works without invite code (only signup requires it)

---

### Test 8: Test UI Toggle Between Login/Signup ✓

1. **On Login screen**:
   - Verify: Email field is HIDDEN
   - Verify: Invite Code field is HIDDEN
   - Verify: Button says "Login"

2. **Tap "Don't have an account? Sign Up"**:
   - Verify: Email field is VISIBLE
   - Verify: Invite Code field is VISIBLE
   - Verify: Button says "Sign Up"

3. **Tap "Already have an account? Login"**:
   - Verify: Email field is HIDDEN
   - Verify: Invite Code field is HIDDEN
   - Verify: Button says "Login"

**Expected Result**: ✅ UI correctly shows/hides fields based on mode

---

## Database Verification Queries

### Check Invite Code Usage

```sql
-- View all invite codes with status
SELECT 
    code,
    used,
    CASE WHEN used THEN 'USED' ELSE 'AVAILABLE' END as status,
    created_at
FROM invite_codes
ORDER BY created_at DESC;
```

### Check Which User Used Which Code

```sql
-- See user-code relationships
SELECT 
    ic.code,
    u.username,
    u.email,
    u.last_login
FROM invite_codes ic
LEFT JOIN users u ON ic.used_by = u.id
WHERE ic.used = TRUE
ORDER BY u.last_login DESC;
```

### Get Statistics

```sql
-- Overall statistics
SELECT 
    COUNT(*) as total_codes,
    SUM(CASE WHEN used = TRUE THEN 1 ELSE 0 END) as used_codes,
    SUM(CASE WHEN used = FALSE THEN 1 ELSE 0 END) as unused_codes,
    ROUND(100.0 * SUM(CASE WHEN used = TRUE THEN 1 ELSE 0 END) / COUNT(*), 2) as usage_percentage
FROM invite_codes;
```

---

## Troubleshooting

### Issue: "Invalid invite code" for valid code

**Possible Causes**:
1. Code doesn't exist in database
2. Typo in code entry
3. Database not set up

**Solutions**:
```sql
-- Check if code exists
SELECT * FROM invite_codes WHERE code = 'YOUR-CODE-HERE';

-- If not found, insert it
INSERT INTO invite_codes (code) VALUES ('YOUR-CODE-HERE');
```

### Issue: App crashes on signup

**Possible Causes**:
1. Database table doesn't exist
2. Supabase credentials not configured

**Solutions**:
1. Run the database migration SQL
2. Check `local.properties` has correct SUPABASE_URL and SUPABASE_KEY

### Issue: "This invite code has already been used"

**This is expected behavior!** Each code can only be used once.

**To test again**:
```sql
-- Reset a code (for testing only!)
UPDATE invite_codes 
SET used = FALSE, used_by = NULL 
WHERE code = 'TEST-2024-001';
```

### Issue: Can't see invite code field

**Check**:
1. Are you on the SIGNUP screen? (not login)
2. Did you tap "Don't have an account? Sign Up"?
3. The field should appear below Password field

---

## Success Criteria Checklist

- [ ] App builds successfully
- [ ] App installs on device
- [ ] Database table created
- [ ] Test codes inserted
- [ ] Signup with valid code succeeds
- [ ] Signup with invalid code fails
- [ ] Signup with used code fails
- [ ] Signup with empty code fails
- [ ] Code marked as used after signup
- [ ] Code linked to correct user
- [ ] Login works without invite code
- [ ] UI toggles correctly
- [ ] Multiple signups with different codes work
- [ ] Statistics queries return correct data

---

## Quick Test Commands

### Python Script Testing

```bash
# Generate 5 test codes
python admin_tools/generate_invite_codes.py --count 5 --prefix TEST

# List unused codes
python admin_tools/generate_invite_codes.py --list

# Show statistics
python admin_tools/generate_invite_codes.py --stats
```

### SQL Quick Tests

```sql
-- Quick check: How many codes are available?
SELECT COUNT(*) FROM invite_codes WHERE used = FALSE;

-- Quick check: Recent signups
SELECT u.username, ic.code, u.last_login
FROM users u
JOIN invite_codes ic ON ic.used_by = u.id
ORDER BY u.last_login DESC
LIMIT 5;
```

---

## Test Results Template

Copy this and fill in your results:

```
=== INVITE CODE SYSTEM TEST RESULTS ===

Date: _______________
Tester: _______________
Device: Redmi 8 (Android 10)

[ ] Test 1: App Installation - PASS/FAIL
[ ] Test 2: Valid Invite Code - PASS/FAIL
[ ] Test 3: Invalid Invite Code - PASS/FAIL
[ ] Test 4: Used Invite Code - PASS/FAIL
[ ] Test 5: Empty Invite Code - PASS/FAIL
[ ] Test 6: Multiple Signups - PASS/FAIL
[ ] Test 7: Login (No Code) - PASS/FAIL
[ ] Test 8: UI Toggle - PASS/FAIL

Notes:
_________________________________
_________________________________
_________________________________

Overall Status: PASS/FAIL
```

---

## Next Steps After Testing

1. ✅ Verify all tests pass
2. ✅ Generate production invite codes
3. ✅ Document code distribution process
4. ✅ Set up monitoring queries
5. ✅ Plan code generation schedule
6. ✅ Train team on admin tools
7. ✅ Deploy to production

---

**Happy Testing! 🚀**
