import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "dev.oddhealth.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.oddhealth.app"
        minSdk = 31
        targetSdk = 36
        // Release builds take the version from the git tag: -PversionName=1.2.3
        // (CI passes it). versionCode is derived from it: 1.2.3 -> 10203.
        val tagVersion = providers.gradleProperty("versionName").orNull?.removePrefix("v")
        versionName = tagVersion ?: "0.14.0"
        versionCode = (tagVersion ?: "0.14.0").split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
    }

    signingConfigs {
        // A fixed debug key, kept in the repo so every debug build installs over the last one.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release key: from android/keystore.properties (git-ignored) or ODD_* environment
        // variables. See docs/release.md. Without one, release builds are left unsigned.
        val props = Properties().apply {
            rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
        }
        fun setting(key: String, env: String): String? = props.getProperty(key) ?: System.getenv(env)
        val storePath = setting("storeFile", "ODD_KEYSTORE")
        if (storePath != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = setting("storePassword", "ODD_KEYSTORE_PASSWORD")
                keyAlias = setting("keyAlias", "ODD_KEY_ALIAS")
                keyPassword = setting("keyPassword", "ODD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":healthconnect"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
