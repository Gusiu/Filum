pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Filum"

// :core:protocol is a plain JVM module with no dependency on Android.
// Its tests therefore run on an ordinary JVM, with no emulator and no Android
// SDK - and this is where the rules live whose mistakes cost a file silently
// destroyed.
include(":core:protocol")
include(":core:crypto")
include(":core:net")
include(":core:data")
include(":core:scan")
include(":core:sync")
include(":app")
