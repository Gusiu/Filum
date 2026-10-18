plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "pl.wspa.filum.crypto"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // KeyPin and PinnedTrustManager do not touch the Android API, so their tests run
    // on an ordinary JVM - no emulator, in seconds.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}


dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
