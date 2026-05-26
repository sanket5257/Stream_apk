# StreamForge Admin Tools

This directory contains administrative tools for managing the StreamForge application.

## Invite Code System

The invite code system prevents unauthorized users from creating unlimited accounts. Only users with valid invite codes can sign up.

### Database Setup

1. **Run the migration script** in your Supabase SQL editor:
   ```sql
   -- Copy and paste the contents of database_migration.sql
   ```

2. **Verify the table was created**:
   ```sql
   SELECT * FROM invite_codes LIMIT 5;
   ```

### Generating Invite Codes

#### Prerequisites

Install required Python packages:
```bash
pip install supabase python-dotenv
```

#### Configuration

Create a `.env` file in the project root or set environment variables:
```bash
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_KEY=your-anon-key-here
```

#### Usage Examples

**Generate 10 invite codes:**
```bash
python admin_tools/generate_invite_codes.py --count 10
```

**Generate codes with a custom prefix:**
```bash
python admin_tools/generate_invite_codes.py --count 50 --prefix STREAM
```

**Generate longer codes:**
```bash
python admin_tools/generate_invite_codes.py --count 5 --length 12
```

**List unused invite codes:**
```bash
python admin_tools/generate_invite_codes.py --list
```

**Show statistics:**
```bash
python admin_tools/generate_invite_codes.py --stats
```

### Manual Code Generation (SQL)

You can also manually insert invite codes using SQL:

```sql
-- Insert a single code
INSERT INTO invite_codes (code) VALUES ('YOUR-CODE-HERE');

-- Insert multiple codes
INSERT INTO invite_codes (code) VALUES 
    ('WELCOME2024'),
    ('BETA-ACCESS-001'),
    ('STREAMFORGE-VIP');
```

### Monitoring Invite Codes

**Check all unused codes:**
```sql
SELECT code, created_at 
FROM invite_codes 
WHERE used = FALSE 
ORDER BY created_at DESC;
```

**Check used codes with user info:**
```sql
SELECT 
    ic.code,
    ic.created_at,
    u.username,
    u.email
FROM invite_codes ic
LEFT JOIN users u ON ic.used_by = u.id
WHERE ic.used = TRUE
ORDER BY ic.created_at DESC;
```

**Get statistics:**
```sql
SELECT 
    COUNT(*) as total_codes,
    SUM(CASE WHEN used = TRUE THEN 1 ELSE 0 END) as used_codes,
    SUM(CASE WHEN used = FALSE THEN 1 ELSE 0 END) as unused_codes
FROM invite_codes;
```

### Security Best Practices

1. **Keep invite codes secure** - Don't commit them to version control
2. **Generate codes with sufficient entropy** - Use at least 10 characters
3. **Monitor usage** - Regularly check for suspicious patterns
4. **Rotate codes** - Delete old unused codes periodically
5. **Limit distribution** - Only share codes with trusted users

### Troubleshooting

**Error: "Missing Supabase credentials"**
- Ensure `SUPABASE_URL` and `SUPABASE_KEY` are set in your environment or `.env` file

**Error: "duplicate key value violates unique constraint"**
- The code already exists in the database. The script will skip it and continue.

**Error: "relation 'invite_codes' does not exist"**
- Run the `database_migration.sql` script first to create the table

### Database Schema

```sql
CREATE TABLE invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) UNIQUE NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

**Columns:**
- `id`: Unique identifier (UUID)
- `code`: The invite code string (unique)
- `used`: Whether the code has been used
- `used_by`: User ID who used this code (NULL if unused)
- `created_at`: Timestamp when the code was created

### How It Works

1. **Admin generates invite codes** using the Python script or SQL
2. **Admin distributes codes** to authorized users
3. **User enters code** during signup in the Android app
4. **App validates code** by checking:
   - Code exists in database
   - Code hasn't been used yet
5. **App creates account** if code is valid
6. **App marks code as used** and links it to the new user
7. **Code becomes invalid** for future signups

This ensures each invite code can only be used once, preventing unlimited account creation.
