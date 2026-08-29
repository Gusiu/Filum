plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "pl.wspa.filum.app"

    // compileSdk i targetSdk to dwie różne rzeczy. Kompilujemy wobec 37, bo tego
    // wymagają nowsze biblioteki AndroidX, ale zachowanie systemu wybiera targetSdk
    // niżej - i to on zostaje na 36 zgodnie z ADR-013.
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.wspa.filum"
        minSdk = 30

        // targetSdk 36, a nie 37, celowo (ADR-013): pozwala na razie ominąć wymóg
        // ACCESS_LOCAL_NETWORK z Androida 17. Dystrybucja i tak odbywa się poza
        // sklepem, bo Google Play nie dopuszcza MANAGE_EXTERNAL_STORAGE dla
        // aplikacji tego rodzaju.
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
}


dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:crypto"))
    implementation(project(":core:net"))
    implementation(project(":core:data"))
    implementation(project(":core:scan"))
    implementation(project(":core:sync"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
