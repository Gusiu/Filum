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

// :core:protocol jest czystym modułem JVM, bez zależności od Androida.
// Dzięki temu jego testy uruchamiają się na zwykłej maszynie wirtualnej,
// bez emulatora i bez Android SDK — a to właśnie tutaj mieszkają reguły,
// których błąd kosztuje ciche skasowanie pliku.
include(":core:protocol")
include(":core:crypto")
include(":core:net")
