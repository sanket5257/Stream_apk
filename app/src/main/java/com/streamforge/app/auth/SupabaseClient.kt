package com.streamforge.app.auth

import com.streamforge.app.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime

/**
 * Supabase client singleton.
 * 
 * Credentials are loaded from BuildConfig (which reads from local.properties or environment variables).
 * 
 * To configure:
 * 1. Create/edit local.properties in project root
 * 2. Add:
 *    SUPABASE_URL=https://xxxxx.supabase.co
 *    SUPABASE_KEY=your_anon_key_here
 * 
 * Or set environment variables: SUPABASE_URL and SUPABASE_KEY
 */
object SupabaseClient {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_KEY
    ) {
        install(Postgrest)
        install(Realtime)
    }
}
