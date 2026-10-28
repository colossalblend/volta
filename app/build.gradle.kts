plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.ars3nb.volta"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ars3nb.volta"
        minSdk = 26
        targetSdk = 36
        // Every CI build is numbered by its run, so each one is newer than the last and
        // the in-app updater can tell which build is the latest. Local builds stay at 1.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (run != null) 100 + run else 1
        versionName = if (run != null) "1.1.$run" else "1.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Stable app identity for HWID salt (do not change after release)
        buildConfigField("String", "HWID_SALT", "\"volta-hwid-v1\"")
        buildConfigField("String", "APP_BRAND", "\"Volta\"")

        // Where the in-app updater looks for newer builds: the rolling pre-release of the
        // branch this build came from, in the public mirror first, then the repository
        // itself (private, so only with a token entered in the app).
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "colossalblend/volta"
        val mirror = repo.substringBefore('/') + "/volta-releases"
        buildConfigField("String", "UPDATE_REPOS", "\"$mirror,$repo\"")
        buildConfigField("String", "UPDATE_TAG", "\"${System.getenv("VOLTA_UPDATE_TAG") ?: "apk-main"}\"")
    }

    signingConfigs {
        // One fixed debug key for every machine and CI run, so a newer APK always
        // installs over an older one instead of failing on a signature mismatch.
        getByName("debug") {
            storeFile = file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystore = System.getenv("VOLTA_KEYSTORE_FILE")
            if (!keystore.isNullOrBlank() && file(keystore).exists()) {
                storeFile = file(keystore)
                storePassword = System.getenv("VOLTA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VOLTA_KEY_ALIAS")
                keyPassword = System.getenv("VOLTA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseKey = signingConfigs.getByName("release")
            signingConfig = if (releaseKey.storeFile != null) releaseKey else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The Xray core is ~35 MB per CPU type and is stored uncompressed (see packaging),
    // so a universal APK weighs 150 MB. One APK per ABI keeps a phone download at ~40 MB;
    // the universal one stays for anyone unsure which to take.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Unpacked on install: the OpenFlux helper (libopenflux.so) is a program, and
            // Android lets an app run programs only from its unpacked library folder.
            // That costs an unpacked copy of the Xray core on the phone, but compressed
            // libraries make the APK itself smaller to download.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // Optional Xray core — place libv2ray.aar into app/libs/
    val xrayAar = file("libs/libv2ray.aar")
    if (xrayAar.exists() && xrayAar.length() > 1024) {
        implementation(files(xrayAar))
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.okhttp)
    implementation(libs.androidx.webkit)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
