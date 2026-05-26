# 🔧 Troubleshooting: "Invalid Code" Error

## Problem: App shows "Invalid invite code" error

This means the invite code doesn't exist in your database yet.

---

## ✅ Solution: Setup Database (3 Steps)

### Step 1: Open Supabase SQL Editor

1. Go to: https://supabase.com/dashboard
2. Select your StreamForge project
3. Click **"SQL Editor"** in the left sidebar
4. Click **"New query"** button

### Step 2: Run Setup Script

**Copy this ENTIRE script and paste into SQL Editor:**

```sql
-- Create invite_codes table
CREATE TABLE IF NOT EXISTS invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_invite_codes_code ON invite_codes(code);
CREATE INDEX IF NOT EXISTS idx_invite_codes_used ON invite_codes(used);

-- Insert test codes
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

-- Verify setup
SELECT code, used, created_at FROM invite_codes ORDER BY created_at DESC;
```

**Then click the green "Run" button** (or press Ctrl+Enter)

### Step 3: Verify Codes Were Created

You should see a table showing all 10 codes:

```
code              | used  | created_at
------------------+-------+------------------------
TEST-2024-001     | false | 2026-05-26 ...
TEST-2024-002     | false | 2026-05-26 ...
TEST-2024-003     | false | 2026-05-26 ...
...
```

---

## 🧪 Test Again on Your Phone

Now try signing up again with:

```
Email: test1@example.com
Username: testuser1
Password: password123
Invite Code: TEST-2024-001
```

**Should work now!** ✅

---

## 🔍 Still Not Working? Run Verification

If it still shows "invalid code", run this verification script:

**In Supabase SQL Editor, run:**

```sql
-- Check if table exists
SELECT COUNT(*) as total_codes FROM invite_codes;

-- Check if specific code exists
SELECT * FROM invite_codes WHERE code = 'TEST-2024-001';

-- Show all codes
SELECT code, used FROM invite_codes;
```

**Expected Results:**
- `total_codes` should be 10 or more
- `TEST-2024-001` should exist with `used = false`
- You should see a list of all codes

---

## 🐛 Common Issues

### Issue 1: "relation 'invite_codes' does not exist"

**Cause**: Table wasn't created  
**Solution**: Run the setup script above

### Issue 2: Query returns 0 codes

**Cause**: INSERT statement didn't run  
**Solution**: Run just the INSERT part:

```sql
INSERT INTO invite_codes (code) VALUES 
    ('TEST-2024-001'),
    ('TEST-2024-002'),
    ('TEST-2024-003');
```

### Issue 3: "duplicate key value violates unique constraint"

**Cause**: Codes already exist (this is actually good!)  
**Solution**: Just check existing codes:

```sql
SELECT code FROM invite_codes WHERE used = FALSE;
```

Use any of the codes shown.

### Issue 4: Code exists but still shows "invalid"

**Possible causes**:
1. Typo in the code (check spelling/case)
2. Extra spaces in the code
3. App not connected to correct Supabase project

**Solution**: 
- Try copying the code directly from database
- Check `local.properties` has correct SUPABASE_URL and SUPABASE_KEY
- Rebuild and reinstall the app:
  ```bash
  cd Stream_apk
  ./gradlew clean installDebug
  ```

---

## 📱 Quick Test: Insert a Simple Code

Try this super simple code:

```sql
-- Insert a simple test code
INSERT INTO invite_codes (code) VALUES ('TEST123');

-- Verify it exists
SELECT * FROM invite_codes WHERE code = 'TEST123';
```

Then try signing up with: `TEST123`

---

## 🔐 Check Supabase Connection

Verify your app is connected to the right Supabase project:

1. Open: `Stream_apk/local.properties`
2. Check these values:
   ```properties
   SUPABASE_URL=https://xxxxx.supabase.co
   SUPABASE_KEY=your_anon_key_here
   ```
3. Make sure they match your Supabase dashboard:
   - Dashboard → Settings → API → Project URL
   - Dashboard → Settings → API → anon/public key

---

## 🆘 Emergency: Manual Code Insert

If nothing else works, manually insert a code in Supabase:

1. Go to Supabase Dashboard
2. Click **"Table Editor"** (not SQL Editor)
3. Find `invite_codes` table
4. Click **"Insert row"**
5. Fill in:
   - `code`: `MYCODE123`
   - `used`: `false`
   - Leave other fields empty
6. Click **"Save"**

Then try signing up with: `MYCODE123`

---

## ✅ Verification Checklist

Run through this checklist:

- [ ] Supabase SQL Editor opened
- [ ] Setup script executed successfully
- [ ] Query returned list of codes
- [ ] `TEST-2024-001` appears in the list
- [ ] `used` column shows `false`
- [ ] App rebuilt and reinstalled
- [ ] Correct Supabase credentials in local.properties
- [ ] Tried signup with exact code: `TEST-2024-001`

---

## 📞 Need More Help?

If you've tried everything above and it still doesn't work:

1. **Check app logs**:
   ```bash
   adb logcat | grep -i "invite"
   ```

2. **Verify database connection**:
   ```sql
   -- This should return your users
   SELECT COUNT(*) FROM users;
   ```

3. **Check InviteCodeManager logs**:
   Look for "InviteCodeManager" or "Validating invite code" in logs

4. **Rebuild from scratch**:
   ```bash
   cd Stream_apk
   ./gradlew clean
   ./gradlew assembleDebug
   ./gradlew installDebug
   ```

---

## 🎯 Most Likely Solution

**99% of the time, the issue is:**

The database setup script wasn't run yet!

**Solution**: Copy the setup script from the top of this document and run it in Supabase SQL Editor.

That's it! 🎉
