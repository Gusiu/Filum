plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "pl.wspa.filum.scan"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // FilesystemScanner, ExclusionRules and mtime normalisation do not touch the Android
    // API, so we test them on real files in a temporary directory, with no emulator.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}


dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
