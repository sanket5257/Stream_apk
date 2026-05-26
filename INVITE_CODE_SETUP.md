# Invite Code System - Setup Guide

## Quick Start

### 1. Database Setup

Run the migration script in your Supabase SQL Editor:

```bash
# Navigate to Supabase Dashboard > SQL Editor
# Copy and paste the contents of: admin_tools/database_migration.sql
```

### 2. Generate Invite Codes

```bash
# Install Python dependencies
pip install supabase python-dotenv

# Set environment variables
export SUPABASE_URL="https://your-project.supabase.co"
export SUPABASE_KEY="your-anon-key-here"

# Generate 10 invite codes
python admin_tools/generate_invite_codes.py --count 10
```

### 3. Distribute Codes

Share the generated codes with authorized users. Each code can only be used once.

### 4. Users Sign Up

Users enter the invite code during signup in the Android app.

## Features Implemented

✅ **InviteCodeManager.kt** - Validates and manages invite codes
✅ **InviteCode.kt** - Data model for invite codes
✅ **Updated AuthManager.kt** - Requires invite code during signup
✅ **Updated LoginActivity.kt** - UI for invite code input
✅ **Updated activity_login.xml** - Invite code input field
✅ **database_migration.sql** - PostgreSQL table creation
✅ **generate_invite_codes.py** - Python script to generate codes
✅ **Admin documentation** - Complete setup and usage guide

## Security Features

- Each invite code can only be used once
- Codes are validated before account creation
- Used codes are linked to user accounts
- Database constraints prevent duplicate codes
- Atomic operations prevent race conditions

## Monitoring

Check invite code usage:

```sql
-- View all unused codes
SELECT code, created_at FROM invite_codes WHERE used = FALSE;

-- View used codes with user info
SELECT ic.code, u.username, u.email 
FROM invite_codes ic
JOIN users u ON ic.used_by = u.id
WHERE ic.used = TRUE;

-- Get statistics
SELECT 
    COUNT(*) as total,
    SUM(CASE WHEN used THEN 1 ELSE 0 END) as used,
    SUM(CASE WHEN NOT used THEN 1 ELSE 0 END) as unused
FROM invite_codes;
```

## Files Modified/Created

### Android App
- `app/src/main/java/com/streamforge/app/auth/InviteCodeManager.kt` (NEW)
- `app/src/main/java/com/streamforge/app/auth/models/InviteCode.kt` (NEW)
- `app/src/main/java/com/streamforge/app/auth/AuthManager.kt` (MODIFIED)
- `app/src/main/java/com/streamforge/app/auth/LoginActivity.kt` (MODIFIED)
- `app/src/main/res/layout/activity_login.xml` (MODIFIED)

### Admin Tools
- `admin_tools/database_migration.sql` (NEW)
- `admin_tools/generate_invite_codes.py` (NEW)
- `admin_tools/README.md` (NEW)

## Next Steps

1. Run the database migration
2. Generate initial invite codes
3. Test the signup flow with a valid code
4. Test with an invalid/used code to verify error handling
5. Monitor code usage in production

For detailed documentation, see `admin_tools/README.md`
