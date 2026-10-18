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

    // The route race and the HTTP client can be tested on an ordinary JVM, together
    // with real TLS and mTLS - MockWebServer performs a genuine handshake.
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
