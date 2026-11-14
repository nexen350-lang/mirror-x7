plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.bouncycastle.pkix)

    testImplementation(libs.junit)
}
