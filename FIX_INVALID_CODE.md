# 🔧 FIX: "Invalid Invite Code" Error

## The Problem

You're seeing "Invalid invite code" because the database table doesn't exist yet or has no codes in it.

---

## ✅ THE FIX (Follow These Exact Steps)

### Step 1: Open Supabase Dashboard

1. Open your browser
2. Go to: **https://supabase.com/dashboard**
3. Click on your **StreamForge project**

### Step 2: Open SQL Editor

1. Look at the left sidebar
2. Click on **"SQL Editor"** (it has a database icon)
3. Click the **"New query"** button at the top

### Step 3: Copy This SQL Code

**COPY EVERYTHING BELOW** (Ctrl+A, Ctrl+C):

```sql
-- Create the invite_codes table
CREATE TABLE IF NOT EXISTS invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Create indexes for fast lookups
CREATE INDEX IF NOT EXISTS idx_invite_codes_code ON invite_codes(code);
CREATE INDEX IF NOT EXISTS idx_invite_codes_used ON invite_codes(used);

-- Insert 10 test codes you can use immediately
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

-- Show all the codes that were created
SELECT 
    code,
    used,
    created_at
FROM invite_codes
ORDER BY created_at DESC;
```

### Step 4: Paste and Run

1. **Paste** the SQL code into the SQL Editor (Ctrl+V)
2. Click the green **"Run"** button (or press Ctrl+Enter)
3. Wait 2-3 seconds

### Step 5: Verify Success

You should see a table with results like this:

```
code              | used  | created_at
------------------+-------+------------------------
TEST-2024-001     | false | 2026-05-26 10:30:00
TEST-2024-002     | false | 2026-05-26 10:30:00
TEST-2024-003     | false | 2026-05-26 10:30:00
...
```

✅ **If you see this table, you're done!**

---

## 📱 Now Test on Your Phone

1. **Open StreamForge app** on your Redmi 8
2. **Tap "Don't have an account? Sign Up"**
3. **Fill in**:
   ```
   Email: mytest@example.com
   Username: myusername
   Password: password123
   Invite Code: TEST-2024-001
   ```
4. **Tap "Sign Up"**

### ✅ Expected Result:
- Green toast message: "Account created successfully!"
- Automatically logged in
- Taken to main screen

### ❌ If Still Shows "Invalid Code":

Try these codes one by one:
- `TEST-2024-002`
- `TEST-2024-003`
- `WELCOME-BETA`
- `STREAM-VIP-99`

---

## 🔍 Still Not Working? Check This

### Verify Table Exists

Run this in Supabase SQL Editor:

```sql
SELECT COUNT(*) as total_codes FROM invite_codes;
```

**Expected**: Should show a number (like 10)  
**If error**: Table doesn't exist, run Step 3 again

### Verify Specific Code Exists

```sql
SELECT * FROM invite_codes WHERE code = 'TEST-2024-001';
```

**Expected**: Should show 1 row with the code  
**If empty**: Code doesn't exist, run the INSERT part again

### Check All Available Codes

```sql
SELECT code FROM invite_codes WHERE used = FALSE;
```

**This shows all codes you can use**

---

## 🆘 Emergency: Create One Simple Code

If nothing works, try this super simple approach:

```sql
-- Delete everything and start fresh
DROP TABLE IF EXISTS invite_codes CASCADE;

-- Create table
CREATE TABLE invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_by UUID,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Insert ONE simple code
INSERT INTO invite_codes (code) VALUES ('TESTCODE');

-- Check it exists
SELECT * FROM invite_codes;
```

Then try signing up with: **`TESTCODE`**

---

## 📋 Quick Checklist

Before testing, verify:

- [ ] Ran the SQL script in Supabase
- [ ] Saw the results table with codes
- [ ] Codes show `used = false`
- [ ] Using exact code from database (no typos)
- [ ] App is connected to correct Supabase project

---

## 🎯 Most Common Mistakes

1. **Didn't run the SQL script** ← This is 90% of cases!
2. **Typo in the invite code** (e.g., `TEST-2024-O01` instead of `TEST-2024-001`)
3. **Extra spaces** in the code
4. **Wrong Supabase project** selected

---

## ✅ Success Indicators

You'll know it's working when:

1. SQL query shows codes in database ✅
2. App shows "Account created successfully!" ✅
3. You're automatically logged in ✅
4. Running this SQL shows the code as used:
   ```sql
   SELECT * FROM invite_codes WHERE code = 'TEST-2024-001';
   -- Should show: used = true
   ```

---

## 📞 Still Stuck?

If you've done all the above and it still doesn't work:

1. **Check app logs**:
   - Look for "InviteCodeManager" in Android Studio Logcat
   - Should show: "Validating invite code: TEST-2024-001"

2. **Verify Supabase connection**:
   - Check `local.properties` has correct credentials
   - Try querying users table: `SELECT COUNT(*) FROM users;`

3. **Rebuild app**:
   ```bash
   cd Stream_apk
   ./gradlew clean installDebug
   ```

---

## 💡 Pro Tip

After running the SQL script, you have **10 codes** ready to use:

1. TEST-2024-001
2. TEST-2024-002
3. TEST-2024-003
4. TEST-2024-004
5. TEST-2024-005
6. WELCOME-BETA
7. STREAM-VIP-99
8. EARLY-ACCESS
9. BETA-TESTER
10. INVITE-ALPHA

Each can be used once. After using one, try the next!

---

**The fix is simple: Run the SQL script in Supabase. That's it!** 🎉
