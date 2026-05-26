-- StreamForge Authentication System
-- Username/Password Login with Single Device Binding
-- Run this SQL in your Supabase SQL Editor

-- Drop old tables if they exist
DROP TABLE IF EXISTS devices CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- Create users table with single device binding
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT UNIQUE NOT NULL,
    username TEXT UNIQUE NOT NULL,
    password TEXT NOT NULL,
    is_active BOOLEAN DEFAULT true,
    active_device_id TEXT,
    device_name TEXT,
    device_model TEXT,
    last_login TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT now()
);

-- Create index for faster lookups
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_active_device ON users(active_device_id);

-- Enable Row Level Security (RLS)
ALTER TABLE users ENABLE ROW LEVEL SECURITY;

-- Create policy to allow reading user data (for login)
CREATE POLICY "Allow read access to users" ON users
    FOR SELECT
    USING (true);

-- Create policy to allow inserting new users (for signup)
CREATE POLICY "Allow insert new users" ON users
    FOR INSERT
    WITH CHECK (true);

-- Create policy to allow updating device binding
CREATE POLICY "Allow update device binding" ON users
    FOR UPDATE
    USING (true);

-- Grant permissions
GRANT SELECT, INSERT, UPDATE ON users TO anon;
GRANT SELECT, INSERT, UPDATE ON users TO authenticated;

-- Display message
SELECT 'Database setup complete! Users can now sign up.' as message;
