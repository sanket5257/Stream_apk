import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

// Phase 8: signing config skeleton. Reads from ~/.gradle/streamforge-keystore.properties
// if present; otherwise release builds remain unsigned (debug builds always sign with
// the auto-generated debug keystore).
val keystorePropsFile = file(System.getProperty("user.home") + "/.gradle/streamforge-keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// Load local.properties for Supabase credentials
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
}

/**
 * Where installed apps look for new releases.
 *
 * This resolves to `version.json` at the repo root on `main`, which is the file every shipped
 * build polls. It is committed here rather than left to local.properties because an APK built
 * without it can never be updated — see the comment on UPDATE_MANIFEST_URL below.
 */
val DEFAULT_UPDATE_MANIFEST_URL =
    "https://raw.githubusercontent.com/sanket5257/Stream_apk/main/version.json"

android {
    namespace = "com.streamforge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streamforge.app"
        minSdk = 24
        targetSdk = 34
        // VERSIONING — read before changing either number.
        //
        // versionName is what people see. 1.0.4 is the graphics release: it fixes the bad
        // regex that stopped PackRasterizer loading at all — so nothing drew, anywhere — and
        // adds the Diagnostics screen that made the failure findable on a phone with no
        // USB debugging. A graphic added on the Graphics screen also now reaches a studio
        // that was already open, and one that fails to draw says why instead of an empty box.
        //
        // 1.0.3 (code 7) was built and hand-installed for testing but never published, so it
        // is burnt: a device already carrying code 7 would refuse an equal code. This is why
        // the public release is 1.0.4 / code 8 — a test build spends a code just as a shipped
        // one does.
        //
        // versionCode is the ONLY value Android compares, and it can NEVER go down OR repeat.
        // 1.0.0 shipped as 4, 1.0.1 as 5, 1.0.2 as 6 and 1.0.3 as 7, so this must be 8.
        // Tagging a GitHub release "v1.0.4" does NOT change these numbers — they live here, and an APK built without
        // bumping them carries the old version as far as every phone is concerned, which is
        // exactly how a "released" update reaches nobody.
        //
        // A device only offers an update when the manifest's versionCode is strictly GREATER
        // than the installed one. Shipping the same code twice means no one is ever prompted,
        // however many GitHub releases exist.
        versionCode = 8
        versionName = "1.0.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        
        // Supabase credentials from local.properties or environment variables
        val supabaseUrl = localProps.getProperty("SUPABASE_URL")
            ?: System.getenv("SUPABASE_URL") 
            ?: "YOUR_SUPABASE_URL"
        val supabaseKey = localProps.getProperty("SUPABASE_KEY")
            ?: System.getenv("SUPABASE_KEY") 
            ?: "YOUR_SUPABASE_ANON_KEY"
            
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")

        // Update channel: the JSON manifest the app polls for new releases (see RELEASING.md).
        //
        // This has a DEFAULT rather than being blank, and that matters. Distribution is
        // direct-APK only: a device gets the installer once, and every release after that has
        // to arrive through this URL. A build shipped with a blank manifest URL has no update
        // channel at all — those installs are stranded on that version permanently, with no
        // way to reach them short of asking each user to sideload again. Defaulting it means
        // you have to go out of your way to ship an un-updatable build, instead of getting one
        // by forgetting a line in local.properties.
        //
        // Override in local.properties if you host the manifest somewhere else.
        val updateManifestUrl = localProps.getProperty("UPDATE_MANIFEST_URL")
            ?: System.getenv("UPDATE_MANIFEST_URL")
            ?: DEFAULT_UPDATE_MANIFEST_URL
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")

        // How customers reach you to buy a licence. There is no payment gateway in the app:
        // the Upgrade screen shows the plans and then hands off to these. Any field left
        // blank simply hides its button, so a build with none of them set still works — it
        // just can't sell anything. Set them in local.properties.
        buildConfigField(
            "String", "SUPPORT_WHATSAPP",
            "\"${localProps.getProperty("SUPPORT_WHATSAPP") ?: System.getenv("SUPPORT_WHATSAPP") ?: ""}\""
        )
        buildConfigField(
            "String", "SUPPORT_PHONE",
            "\"${localProps.getProperty("SUPPORT_PHONE") ?: System.getenv("SUPPORT_PHONE") ?: ""}\""
        )
        buildConfigField(
            "String", "SUPPORT_EMAIL",
            "\"${localProps.getProperty("SUPPORT_EMAIL") ?: System.getenv("SUPPORT_EMAIL") ?: ""}\""
        )
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Minification disabled for now: guarantees Supabase/ktor/serialization work in
            // the distributable without device-tested R8 keep rules. Re-enable once verified.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Robolectric needs the merged resources and manifest to stand up an Android context.
    testOptions { unitTests { isIncludeAndroidResources = true } }

    buildFeatures {
        // viewBinding stays on: the Compose migration is incremental, and the camera
        // screen still hosts RootEncoder's OpenGlView + OverlayEditorView from XML.
        viewBinding = true
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Output APK filename: streamforge.apk (lands in apk/debug/ or apk/release/).
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "streamforge.apk"
        }
    }
}

/**
 * Release-build safety net.
 *
 * Distribution is direct-APK: a user installs once by hand, and every release after that must
 * arrive through the in-app updater. Two mistakes make a release permanently unreachable, and
 * both are silent at build time:
 *
 *  - **No update channel.** The APK never polls for anything. Those installs are stranded.
 *  - **Wrong / missing signing key.** Android refuses an update signed with a different key,
 *    so users would have to uninstall (losing their settings and licence binding) to move on.
 *
 * Both are cheap to check and catastrophic to discover after the APK is in customers' hands,
 * so the build stops rather than producing an unshippable artifact.
 *
 * Values are read into locals here so the task action captures plain booleans — required for
 * Gradle's configuration cache, which this project has enabled.
 */
val hasUpdateChannel = (
    localProps.getProperty("UPDATE_MANIFEST_URL")
        ?: System.getenv("UPDATE_MANIFEST_URL")
        ?: DEFAULT_UPDATE_MANIFEST_URL
    ).isNotBlank()
val hasSigningKey = keystorePropsFile.exists()

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    // Read the flags into locals HERE, at configuration time. Referencing the script-level
    // vals directly from inside doFirst would make the task action capture the build script
    // object itself, which Gradle's configuration cache cannot serialize.
    val updateChannelConfigured = hasUpdateChannel
    val signingKeyPresent = hasSigningKey
    doFirst {
        if (!updateChannelConfigured) {
            throw GradleException(
                "Refusing to build a release with no UPDATE_MANIFEST_URL.\n" +
                    "Every device that installs this APK would be stuck on this version " +
                    "forever — the in-app updater would have nothing to poll.\n" +
                    "Set UPDATE_MANIFEST_URL in local.properties, or unset it to use the " +
                    "default. See RELEASING.md."
            )
        }
        if (!signingKeyPresent) {
            throw GradleException(
                "Refusing to build an unsigned release.\n" +
                    "An APK signed with a different key (or none) cannot update an existing " +
                    "install — users would have to uninstall first, losing their settings and " +
                    "their licence binding.\n" +
                    "Create ~/.gradle/streamforge-keystore.properties. See RELEASING.md."
            )
        }
    }
}

dependencies {
    // Compose. platform() must be applied BEFORE the artifacts the BOM pins, and to every
    // configuration that resolves Compose — androidTest included, or the test variant
    // resolves unpinned versions.
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // Icon pack: Material Symbols as Compose ImageVectors, used across the new UI.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Image loading for overlay + graphics-pack thumbnails.
    implementation(libs.coil.compose)

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.constraintlayout)

    // Material Components
    implementation(libs.material)

    // Storage
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Supabase
    implementation(platform("io.github.jan-tennert.supabase:bom:2.5.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-android:2.3.12")

    // RTMP streaming + OpenGL overlay pipeline
    implementation(libs.rootencoder.library)

    // Tests
    testImplementation(libs.junit)
    // Robolectric runs the platform graphics stack on the JVM, so PackRasterizerTest can
    // assert what a graphics pack actually DRAWS -- not just that its JSON parses.
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
