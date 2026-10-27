import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// By default a failed test prints only the exception type and a line number. On a CI
// runner the build log is all there is, so it has to carry the message as well.
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging.exceptionFormat = TestExceptionFormat.FULL
    }
}
