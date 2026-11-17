plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.oddhealth.healthconnect"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core"))
    api(libs.health.connect)
}
