// AGP 8.13's bundled R8 cannot parse Kotlin 2.4 metadata, which it reports as a warning while
// silently losing Kotlin-aware optimization on a build that is actually shipped minified. Pin a
// newer R8 so the release artifact is shrunk by a version that understands the metadata.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.android.r8)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
