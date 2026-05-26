# Invite Code System - Quick Reference

## Common Commands

### Generate Codes

```bash
# Generate 10 codes
python generate_invite_codes.py --count 10

# Generate with prefix
python generate_invite_codes.py --count 50 --prefix STREAM

# Generate longer codes (12 characters)
python generate_invite_codes.py --count 5 --length 12
```

### View Codes

```bash
# List unused codes
python generate_invite_codes.py --list

# Show statistics
python generate_invite_codes.py --stats
```

## SQL Queries

### View Unused Codes

```sql
SELECT code, created_at 
FROM invite_codes 
WHERE used = FALSE 
ORDER BY created_at DESC;
```

### View Used Codes with User Info

```sql
SELECT 
    ic.code,
    ic.created_at as code_created,
    u.username,
    u.email,
    u.last_login
FROM invite_codes ic
JOIN users u ON ic.used_by = u.id
WHERE ic.used = TRUE
ORDER BY u.last_login DESC;
```

### Statistics

```sql
SELECT 
    COUNT(*) as total_codes,
    SUM(CASE WHEN used = TRUE THEN 1 ELSE 0 END) as used_codes,
    SUM(CASE WHEN used = FALSE THEN 1 ELSE 0 END) as unused_codes,
    ROUND(100.0 * SUM(CASE WHEN used = TRUE THEN 1 ELSE 0 END) / COUNT(*), 2) as usage_percentage
FROM invite_codes;
```

### Manual Code Insertion

```sql
-- Insert single code
INSERT INTO invite_codes (code) VALUES ('YOUR-CODE-HERE');

-- Insert multiple codes
INSERT INTO invite_codes (code) VALUES 
    ('WELCOME2024'),
    ('BETA-ACCESS-001'),
    ('STREAMFORGE-VIP');
```

### Delete Old Unused Codes

```sql
-- Delete codes older than 30 days that haven't been used
DELETE FROM invite_codes 
WHERE used = FALSE 
AND created_at < NOW() - INTERVAL '30 days';
```

### Reset a Used Code (Use with caution!)

```sql
-- Reset a specific code to unused
UPDATE invite_codes 
SET used = FALSE, used_by = NULL 
WHERE code = 'SPECIFIC-CODE-HERE';
```

## Troubleshooting

### "Invalid invite code" error
- Code doesn't exist in database
- Check for typos
- Verify code was generated

### "This invite code has already been used" error
- Code was already used by another user
- Generate a new code for the user

### Python script errors
- Ensure `SUPABASE_URL` and `SUPABASE_KEY` are set
- Install dependencies: `pip install supabase python-dotenv`
- Check network connectivity to Supabase

### Database connection issues
- Verify Supabase credentials
- Check if table exists: `SELECT * FROM invite_codes LIMIT 1;`
- Run migration script if table doesn't exist

## Best Practices

1. **Generate codes in batches** - Create 50-100 at a time
2. **Use prefixes for tracking** - Different prefixes for different campaigns
3. **Monitor usage regularly** - Check statistics weekly
4. **Clean up old codes** - Delete unused codes after 90 days
5. **Keep codes secure** - Don't commit to version control
6. **Document distribution** - Track who received which codes
7. **Set up alerts** - Monitor for unusual signup patterns

## Code Format Examples

- **Standard**: `A2B3C4D5E6`
- **With prefix**: `STREAM-A2B3C4D5E6`
- **Campaign specific**: `BETA2024-XYZ789`
- **VIP codes**: `VIP-GOLD-ABC123`

## Environment Setup

Create `.env` file in project root:

```bash
SUPABASE_URL=https://xxxxx.supabase.co
SUPABASE_KEY=your_anon_key_here
```

Or set environment variables:

```bash
export SUPABASE_URL="https://xxxxx.supabase.co"
export SUPABASE_KEY="your_anon_key_here"
```

## Support

For issues or questions:
1. Check the main README.md in admin_tools/
2. Review database_migration.sql for schema details
3. Verify Supabase dashboard for table structure
4. Check app logs for detailed error messages
