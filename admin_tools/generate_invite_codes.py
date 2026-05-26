#!/usr/bin/env python3
"""
Invite Code Generator for StreamForge

This script generates unique invite codes and inserts them into the Supabase database.
Each code can only be used once for user registration.

Usage:
    python generate_invite_codes.py --count 10
    python generate_invite_codes.py --count 50 --prefix STREAM
    python generate_invite_codes.py --count 5 --length 12

Requirements:
    pip install supabase python-dotenv
"""

import os
import sys
import random
import string
import argparse
from datetime import datetime
from typing import List
from supabase import create_client, Client

# Try to load environment variables from .env file
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    print("Warning: python-dotenv not installed. Using environment variables only.")

def generate_code(length: int = 10, prefix: str = "") -> str:
    """
    Generate a random invite code.
    
    Args:
        length: Length of the random part
        prefix: Optional prefix for the code
    
    Returns:
        Generated invite code
    """
    # Use uppercase letters and numbers (excluding similar looking characters)
    chars = string.ascii_uppercase.replace('O', '').replace('I', '') + string.digits.replace('0', '').replace('1', '')
    random_part = ''.join(random.choices(chars, k=length))
    
    if prefix:
        return f"{prefix}-{random_part}"
    return random_part

def get_supabase_client() -> Client:
    """
    Create and return a Supabase client.
    
    Returns:
        Supabase client instance
    
    Raises:
        ValueError: If required environment variables are not set
    """
    url = os.getenv('SUPABASE_URL')
    key = os.getenv('SUPABASE_KEY')
    
    if not url or not key:
        raise ValueError(
            "Missing Supabase credentials. Please set SUPABASE_URL and SUPABASE_KEY "
            "environment variables or create a .env file with these values."
        )
    
    return create_client(url, key)

def insert_invite_codes(supabase: Client, codes: List[str]) -> tuple[int, int]:
    """
    Insert invite codes into the database.
    
    Args:
        supabase: Supabase client
        codes: List of invite codes to insert
    
    Returns:
        Tuple of (successful_count, failed_count)
    """
    successful = 0
    failed = 0
    
    for code in codes:
        try:
            supabase.table('invite_codes').insert({
                'code': code,
                'used': False
            }).execute()
            successful += 1
            print(f"✓ Generated: {code}")
        except Exception as e:
            failed += 1
            print(f"✗ Failed to insert {code}: {str(e)}")
    
    return successful, failed

def list_unused_codes(supabase: Client, limit: int = 10):
    """
    List unused invite codes from the database.
    
    Args:
        supabase: Supabase client
        limit: Maximum number of codes to display
    """
    try:
        response = supabase.table('invite_codes')\
            .select('code, created_at')\
            .eq('used', False)\
            .order('created_at', desc=True)\
            .limit(limit)\
            .execute()
        
        if response.data:
            print(f"\n📋 Unused Invite Codes (showing {len(response.data)}):")
            print("-" * 50)
            for item in response.data:
                created = datetime.fromisoformat(item['created_at'].replace('Z', '+00:00'))
                print(f"  {item['code']} (created: {created.strftime('%Y-%m-%d %H:%M')})")
        else:
            print("\n⚠️  No unused invite codes found.")
    except Exception as e:
        print(f"\n✗ Error listing codes: {str(e)}")

def get_statistics(supabase: Client):
    """
    Display statistics about invite codes.
    
    Args:
        supabase: Supabase client
    """
    try:
        # Get total count
        total_response = supabase.table('invite_codes').select('id', count='exact').execute()
        total = total_response.count if hasattr(total_response, 'count') else len(total_response.data)
        
        # Get used count
        used_response = supabase.table('invite_codes').select('id', count='exact').eq('used', True).execute()
        used = used_response.count if hasattr(used_response, 'count') else len(used_response.data)
        
        unused = total - used
        
        print("\n📊 Invite Code Statistics:")
        print("-" * 50)
        print(f"  Total Codes:   {total}")
        print(f"  Used Codes:    {used}")
        print(f"  Unused Codes:  {unused}")
        
        if total > 0:
            usage_percent = (used / total) * 100
            print(f"  Usage Rate:    {usage_percent:.1f}%")
    except Exception as e:
        print(f"\n✗ Error getting statistics: {str(e)}")

def main():
    parser = argparse.ArgumentParser(
        description='Generate invite codes for StreamForge signup system',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s --count 10
  %(prog)s --count 50 --prefix STREAM
  %(prog)s --count 5 --length 12
  %(prog)s --list
  %(prog)s --stats
        """
    )
    
    parser.add_argument(
        '--count', '-c',
        type=int,
        default=0,
        help='Number of invite codes to generate (default: 0)'
    )
    
    parser.add_argument(
        '--length', '-l',
        type=int,
        default=10,
        help='Length of the random part of the code (default: 10)'
    )
    
    parser.add_argument(
        '--prefix', '-p',
        type=str,
        default='',
        help='Optional prefix for the codes (e.g., STREAM)'
    )
    
    parser.add_argument(
        '--list',
        action='store_true',
        help='List unused invite codes'
    )
    
    parser.add_argument(
        '--stats',
        action='store_true',
        help='Show invite code statistics'
    )
    
    args = parser.parse_args()
    
    # Show help if no arguments provided
    if len(sys.argv) == 1:
        parser.print_help()
        sys.exit(0)
    
    try:
        # Initialize Supabase client
        print("🔌 Connecting to Supabase...")
        supabase = get_supabase_client()
        print("✓ Connected successfully\n")
        
        # Show statistics if requested
        if args.stats:
            get_statistics(supabase)
        
        # List codes if requested
        if args.list:
            list_unused_codes(supabase)
        
        # Generate codes if count is specified
        if args.count > 0:
            print(f"🎲 Generating {args.count} invite codes...")
            
            # Generate unique codes
            codes = set()
            while len(codes) < args.count:
                code = generate_code(length=args.length, prefix=args.prefix)
                codes.add(code)
            
            # Insert codes into database
            successful, failed = insert_invite_codes(supabase, list(codes))
            
            print(f"\n✅ Summary:")
            print(f"   Successfully generated: {successful}")
            if failed > 0:
                print(f"   Failed: {failed}")
            
            # Show updated statistics
            get_statistics(supabase)
        
    except ValueError as e:
        print(f"\n❌ Configuration Error: {str(e)}")
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ Error: {str(e)}")
        sys.exit(1)

if __name__ == '__main__':
    main()
