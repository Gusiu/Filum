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

    // KeyPin i PinnedTrustManager nie dotykają API Androida, więc ich testy idą
    // na zwykłej maszynie wirtualnej - bez emulatora, w sekundach.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}


dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
