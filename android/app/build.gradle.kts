plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "pl.wspa.filum.app"

    // compileSdk and targetSdk are two different things. We compile against 37 because
    // newer AndroidX libraries require it, but the system behaviour is chosen by the
    // lower targetSdk - and that stays at 36, as ADR-013 sets out.
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.wspa.filum"
        minSdk = 30

        // targetSdk 36 rather than 37, deliberately (ADR-013): for now it avoids the
        // ACCESS_LOCAL_NETWORK requirement of Android 17. Distribution happens outside the
        // store anyway, because Google Play does not allow MANAGE_EXTERNAL_STORAGE for
        // applications of this kind.
        targetSdk = 36

        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // The retry decision does not touch the Android API, so it is tested on an
    // ordinary JVM.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}


dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:crypto"))
    implementation(project(":core:net"))
    implementation(project(":core:data"))
    implementation(project(":core:scan"))
    implementation(project(":core:sync"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
