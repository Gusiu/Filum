plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "pl.wspa.filum.sync"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The engine sits wholly behind interfaces, so it is tested without a network,
    // a database or an emulator.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}


dependencies {
    api(project(":core:protocol"))
    api(project(":core:net"))
    api(project(":core:scan"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // Generates the client key pair for the conformance test, without Android Keystore.
    testImplementation(libs.okhttp.tls)
    testRuntimeOnly(libs.junit.platform.launcher)
}
