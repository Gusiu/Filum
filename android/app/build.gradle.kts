plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/** The release workflow checks this against the server's version and the tag. */
val appVersion = "0.1.0"

/**
 * major * 10000 + minor * 100 + patch, so every release has a greater code than the one
 * before - Android installs an update only over a lower code, which also keeps an older
 * build from being installed over a newer one and its database.
 */
val appVersionCode = appVersion.split('.').map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "versionCode leaves two digits each for minor and patch" }
    major * 10_000 + minor * 100 + patch
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

        versionCode = appVersionCode
        versionName = appVersion
    }

    // Android installs an update only when it carries the same key as the installed app, and
    // replacing the app the other way erases its database: the pairing and the witnessed
    // ledger heads (ADR-019). So a release is signed with one key kept outside the repository,
    // which the release workflow decodes from a secret. Without the key the release build stays
    // unsigned rather than signed with a debug key that differs from one machine to the next.
    val releaseKey = providers.environmentVariable("FILUM_KEYSTORE").orNull
    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey)
                storePassword = providers.environmentVariable("FILUM_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("FILUM_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("FILUM_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
        // A debuggable build that installs next to the regular app. Its own application id
        // gives it its own device key, pairing and database, so device tests and the
        // measurements for the thesis never touch an installation holding a real backup.
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            // The library modules have no qa build type and supply their debug variant.
            matchingFallbacks += "debug"
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

    // Reading the pairing code with the camera, decoded on the device (ADR-018).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
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
