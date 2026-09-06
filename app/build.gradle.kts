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

android {
    namespace = "com.streamforge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.streamforge.app"
        minSdk = 24
        targetSdk = 34
        // versionCode is the ONLY value Android compares when installing an update — it must
        // increase every release or the install is rejected as a downgrade. See RELEASING.md.
        versionCode = 4
        versionName = "0.3.0"

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

        // Sideload update channel: the JSON manifest the app polls for new releases (see
        // RELEASING.md). Left blank the updater simply stays quiet, so builds without it
        // configured behave exactly as before.
        val updateManifestUrl = localProps.getProperty("UPDATE_MANIFEST_URL")
            ?: System.getenv("UPDATE_MANIFEST_URL")
            ?: ""
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
