// App module — declares the Android build + every dependency the
// Sumo voice client needs. Pinned to June-2026 stable versions; bump
// deliberately when needed.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "in.rfidpro.sumo"
    compileSdk = 34

    defaultConfig {
        applicationId = "in.rfidpro.sumo"
        minSdk = 26                  // Android 8.0 — covers ~99% of devices in service
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Backend URL — single-tenant for now. When/if we go multi-customer,
        // surface this as a runtime setting at LoginActivity.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"https://api.pentadsumo.rfidpro.in\""
        )

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // single-purpose app; keep ProGuard config trivial for v1
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }
    composeOptions {
        // Compose Compiler version compatible with Kotlin 1.9.23 —
        // see https://developer.android.com/jetpack/androidx/releases/compose-kotlin
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---- Core + lifecycle ----
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")   // collectAsStateWithLifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // ---- Coroutines (used by Flow + the suspend chain in services) ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ---- Compose UI ----
    val composeBom = platform("androidx.compose:compose-bom:2024.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Network: Retrofit + OkHttp + Moshi (JSON) ----
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // (Cookie persistence is rolled in-house via api/SimpleCookieJar.kt —
    //  the franmontiel/PersistentCookieJar library is unmaintained and
    //  JitPack started 403-ing it. Our ~80-line replacement covers the
    //  exact use case we need: persist the httpOnly refresh cookie across
    //  app launches.)

    // ---- Tests (smoke only — most logic is service-bound + needs an emulator) ----
    testImplementation("junit:junit:4.13.2")
}
