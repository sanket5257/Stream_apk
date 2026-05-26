# 🚀 Quick Start - Test Invite Code System

## ✅ Status: App Built & Installed Successfully!

**Device**: Redmi 8 (Android 10)  
**Build**: ✅ Successful  
**Installation**: ✅ Complete

---

## Step-by-Step Testing (5 Minutes)

### Step 1: Setup Database (2 minutes)

1. **Open Supabase Dashboard**
   - Go to: https://supabase.com/dashboard
   - Select your project

2. **Open SQL Editor**
   - Click "SQL Editor" in left sidebar
   - Click "New query"

3. **Run Quick Setup**
   - Open file: `admin_tools/quick_setup.sql`
   - Copy ALL the SQL code
   - Paste into Supabase SQL Editor
   - Click "Run" button

4. **Verify Success**
   - You should see: "Setup Complete!"
   - Should show 10 codes created
   - Should list available codes

**✅ Database is now ready!**

---

### Step 2: Test Valid Invite Code (1 minute)

1. **Open StreamForge app** on your Redmi 8

2. **Tap "Don't have an account? Sign Up"**

3. **Fill in the form**:
   ```
   Email: test1@example.com
   Username: testuser1
   Password: password123
   Invite Code: TEST-2024-001
   ```

4. **Tap "Sign Up"**

5. **Expected**: 
   - ✅ "Account created successfully!" message
   - ✅ Automatically logged in
   - ✅ Taken to main screen

**✅ Valid code works!**

---

### Step 3: Test Invalid Invite Code (30 seconds)

1. **Logout** from the app

2. **Tap "Don't have an account? Sign Up"**

3. **Fill in the form**:
   ```
   Email: test2@example.com
   Username: testuser2
   Password: password123
   Invite Code: INVALID-999
   ```

4. **Tap "Sign Up"**

5. **Expected**:
   - ❌ Error: "Invalid invite code"
   - ❌ Account NOT created
   - ❌ Still on signup screen

**✅ Invalid code rejected!**

---

### Step 4: Test Used Invite Code (30 seconds)

1. **Stay on signup screen**

2. **Fill in the form with SAME code from Step 2**:
   ```
   Email: test3@example.com
   Username: testuser3
   Password: password123
   Invite Code: TEST-2024-001
   ```
   *(This code was already used)*

3. **Tap "Sign Up"**

4. **Expected**:
   - ❌ Error: "This invite code has already been used"
   - ❌ Account NOT created

**✅ Used code rejected!**

---

### Step 5: Verify in Database (1 minute)

1. **Go back to Supabase SQL Editor**

2. **Run this query**:
   ```sql
   -- Check the code was marked as used
   SELECT 
       ic.code,
       ic.used,
       u.username,
       u.email
   FROM invite_codes ic
   LEFT JOIN users u ON ic.used_by = u.id
   WHERE ic.code = 'TEST-2024-001';
   ```

3. **Expected Result**:
   ```
   code: TEST-2024-001
   used: true
   username: testuser1
   email: test1@example.com
   ```

**✅ Code properly marked as used and linked to user!**

---

## 🎉 Success! System is Working!

If all 5 steps passed, your invite code system is working perfectly:

- ✅ Valid codes allow signup
- ✅ Invalid codes are rejected
- ✅ Used codes cannot be reused
- ✅ Codes are linked to users
- ✅ Database tracking works

---

## Available Test Codes

Use these codes for more testing:

```
TEST-2024-001  ← Already used in testing
TEST-2024-002  ← Available
TEST-2024-003  ← Available
TEST-2024-004  ← Available
TEST-2024-005  ← Available
WELCOME-BETA   ← Available
STREAM-VIP-99  ← Available
EARLY-ACCESS   ← Available
BETA-TESTER    ← Available
INVITE-ALPHA   ← Available
```

---

## Generate More Codes

### Option 1: Using Python Script

```bash
# Install dependencies
pip install supabase python-dotenv

# Setup environment
cp admin_tools/.env.example admin_tools/.env
# Edit .env with your Supabase credentials

# Generate 10 codes
python admin_tools/generate_invite_codes.py --count 10

# Generate with custom prefix
python admin_tools/generate_invite_codes.py --count 20 --prefix STREAM
```

### Option 2: Using SQL

```sql
-- Insert codes manually
INSERT INTO invite_codes (code) VALUES 
    ('YOUR-CODE-1'),
    ('YOUR-CODE-2'),
    ('YOUR-CODE-3');
```

---

## Quick Monitoring

### Check Available Codes

```sql
SELECT code FROM invite_codes WHERE used = FALSE;
```

### Check Recent Signups

```sql
SELECT 
    u.username,
    ic.code,
    u.created_at
FROM users u
JOIN invite_codes ic ON ic.used_by = u.id
ORDER BY u.created_at DESC
LIMIT 10;
```

### Get Statistics

```sql
SELECT 
    COUNT(*) as total,
    SUM(CASE WHEN used THEN 1 ELSE 0 END) as used,
    SUM(CASE WHEN NOT used THEN 1 ELSE 0 END) as available
FROM invite_codes;
```

---

## Troubleshooting

### "Invalid invite code" for TEST-2024-002

**Solution**: Run the quick_setup.sql script again

### App crashes on signup

**Solution**: Check Supabase credentials in local.properties

### Can't see invite code field

**Solution**: Make sure you tapped "Sign Up" (not on Login screen)

---

## Full Documentation

- **Complete Testing Guide**: `TESTING_GUIDE.md`
- **Admin Tools Guide**: `admin_tools/README.md`
- **Quick Reference**: `admin_tools/QUICK_REFERENCE.md`
- **Implementation Summary**: `IMPLEMENTATION_SUMMARY.md`

---

## What's Next?

1. ✅ Test with more codes
2. ✅ Generate production codes
3. ✅ Distribute codes to real users
4. ✅ Monitor usage
5. ✅ Set up regular code generation

**Your invite code system is ready for production! 🎉**
