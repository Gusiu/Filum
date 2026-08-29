plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "pl.wspa.filum.net"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Wyścig tras i klient HTTP dają się przetestować na zwykłej maszynie wirtualnej,
    // razem z prawdziwym TLS i mTLS — MockWebServer zestawia autentyczny uścisk dłoni.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}


dependencies {
    api(project(":core:protocol"))
    api(project(":core:crypto"))
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testRuntimeOnly(libs.junit.platform.launcher)
}
