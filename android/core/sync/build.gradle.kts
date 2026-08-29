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

    // Silnik jest w całości za interfejsami, więc testuje się bez sieci, bazy i emulatora.
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
    testRuntimeOnly(libs.junit.platform.launcher)
}
