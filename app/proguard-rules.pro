# ProGuard / R8 rules for release builds.
# https://www.guardsquare.com/manual/configuration/usage

# Keep RootEncoder classes — reflection used internally.
-keep class com.pedro.** { *; }
-keep interface com.pedro.** { *; }
-dontwarn com.pedro.**

# Keep Kotlin coroutines internals.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Parcelable CREATOR fields.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep custom view constructors (used by layout inflater).
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Optional compile-time annotations referenced by Tink (security-crypto) and slf4j
# (ktor/supabase) that aren't on the runtime classpath. Safe to ignore.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
-dontwarn org.slf4j.impl.StaticLoggerBinder

# kotlinx.serialization: keep the generated serializers for our overlay models so
# polymorphic JSON (OverlayItem sealed class + subclasses) survives minification.
-keepclassmembers class com.streamforge.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.streamforge.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.streamforge.app.**$$serializer { *; }
