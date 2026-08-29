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

    // FilesystemScanner, ExclusionRules i normalizacja mtime nie dotykają API Androida,
    // więc testujemy je na prawdziwych plikach w katalogu tymczasowym, bez emulatora.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}


dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
