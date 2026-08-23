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
 *
 * The client is built lazily rather than in the object initializer. A property initializer
 * that throws (blank/invalid credentials, a ktor engine that can't start) becomes an
 * ExceptionInInitializerError, which poisons the class permanently and takes the whole app
 * down at launch — before any of AuthManager's try/catch can see it. `by lazy` turns the same
 * failure into an ordinary exception thrown at the call site, where it is already handled and
 * surfaces as "login failed" instead of a crash.
 */
object SupabaseClient {
    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_KEY
        ) {
            install(Postgrest)
            install(Realtime)
        }
    }
}
