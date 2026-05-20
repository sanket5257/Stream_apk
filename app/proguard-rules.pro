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
