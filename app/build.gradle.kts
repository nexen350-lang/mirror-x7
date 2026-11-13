import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Only publication tasks require private signing material. Local builds use the machine's
// existing Android debug keystore, never a generated replacement or a publication-key fallback.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingInput(key: String, env: String): String? =
    keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(env).orNull?.takeIf { it.isNotBlank() }

val releaseStorePath = signingInput("storeFile", "SCRCPY_KEYSTORE")
val releaseStore = releaseStorePath?.let { rootProject.file(it) }
val releaseStorePassword = signingInput("storePassword", "SCRCPY_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingInput("keyAlias", "SCRCPY_KEY_ALIAS")
val releaseKeyPassword = signingInput("keyPassword", "SCRCPY_KEY_PASSWORD")
val missingSigningInputs = listOf(
    "storeFile / SCRCPY_KEYSTORE" to releaseStorePath,
    "storePassword / SCRCPY_KEYSTORE_PASSWORD" to releaseStorePassword,
    "keyAlias / SCRCPY_KEY_ALIAS" to releaseKeyAlias,
    "keyPassword / SCRCPY_KEY_PASSWORD" to releaseKeyPassword,
).filter { it.second == null }.map { it.first }

val validatePublishableReleaseSigning = tasks.register("validatePublishableReleaseSigning") {
    group = "verification"
    description = "Rejects unsigned or incompletely configured publishable release artifacts."
    doLast {
        if (missingSigningInputs.isNotEmpty() || releaseStore?.isFile != true) {
            throw GradleException(
                "Publishable release signing is incomplete. Configure all four signing inputs in " +
                    "root keystore.properties or the matching SCRCPY_* environment variables, and " +
                    "provide an existing keystore (relative storeFile paths use the root project). " +
                    "Missing inputs: ${missingSigningInputs.joinToString().ifEmpty { "none" }}. " +
                    "Use assembleLocalRelease for a minified, debug-key-signed sideload APK instead.",
            )
        }
    }
}

// AGP omits installRelease entirely when the signing config is incomplete. Preserve an actionable
// error for that command too, rather than misleading users with "task not found".
if (missingSigningInputs.isNotEmpty()) {
    tasks.register("installRelease") {
        group = "install"
        description = "Explains the missing publication signing configuration."
        dependsOn(validatePublishableReleaseSigning)
    }
}

// Who publishes the app: shown in App info and in the privacy policy. Public, so kept in the
// committed root publisher.properties, which a Gradle property of the same name overrides.
// Read as UTF-8, matching tools/render-privacy-policy.ps1: Properties.load(InputStream) defaults to
// ISO-8859-1 and would garble non-ASCII names such as "José" in the app.
val publisherProperties = Properties().apply {
    val file = rootProject.file("publisher.properties")
    if (file.exists()) file.reader(Charsets.UTF_8).use { load(it) }
}
fun publisherInput(key: String): String =
    providers.gradleProperty(key).orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: publisherProperties.getProperty(key)?.trim().orEmpty()

val publisherName = publisherInput("publisherName")
val publisherEmail = publisherInput("publisherEmail")
val privacyPolicyUrl = publisherInput("privacyPolicyUrl")
val publisherProblems = buildList {
    if (publisherName.isEmpty()) add("publisherName is empty")
    if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(publisherEmail)) add("publisherEmail is not an email address")
    if (!Regex("^https://[^\\s/]+\\.[^\\s]+$").matches(privacyPolicyUrl)) add("privacyPolicyUrl is not an https:// address")
}

val validatePublisherIdentity = tasks.register("validatePublisherIdentity") {
    group = "verification"
    description = "Rejects a signed distribution build that omits developer, contact, or privacy policy metadata."
    doLast {
        if (publisherProblems.isNotEmpty()) {
            throw GradleException(
                "A signed release build must specify publisherName, publisherEmail, and privacyPolicyUrl. " +
                    "public address. Set them in the root publisher.properties (or as Gradle properties of " +
                    "the same names): ${publisherProblems.joinToString("; ")}. " +
                    "Use assembleLocalRelease for a sideload build that needs none of them.",
            )
        }
    }
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val abiSplitsEnabled = providers.gradleProperty("abiSplits").map {
    it.toBooleanStrictOrNull()
        ?: throw GradleException("abiSplits must be explicitly true or false, e.g. -PabiSplits=true.")
}.orElse(false)

android {
    namespace = "com.scrcpy.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.scrcpy.remote"
        minSdk = 26
        targetSdk = 36
        versionCode = 86
        versionName = "3.5.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PUBLISHER_NAME", buildConfigString(publisherName))
        buildConfigField("String", "PUBLISHER_EMAIL", buildConfigString(publisherEmail))
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(privacyPolicyUrl))
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStore
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        create("localRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    splits {
        abi {
            // Explicit opt-in, independent of requested task names. Debug remains universal below.
            isEnable = abiSplitsEnabled.get()
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        lintConfig = file("lint.xml")
        // Dependency trust-manager and obsolete-SDK warnings stay visible in reports.
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The scrcpy server is a jar; make sure it is stored uncompressed-safe and never stripped.
        // aapt2 matches by file-name suffix, so the versioned asset name must be listed
        // (plain "scrcpy-server" never matched scrcpy-server-v5.0.1). Update with the bundled server.
        noCompress += "scrcpy-server-v5.0.1"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        // Debug stays universal. The modern Variant API cannot rename an APK, but it does not
        // need to: with the per-ABI outputs disabled the remaining artifact already uses AGP's
        // default `app-debug.apk` name, so the legacy `applicationVariants` block that AGP 9
        // removes is no longer required.
        variant.outputs.forEach { output -> output.enabled.set(output.filters.isEmpty()) }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Hook real artifact tasks, including abbreviated CLI task selection and aggregate assemble.
// Do not gate preReleaseBuild: lint and unit tests must not require a publication key.
tasks.configureEach {
    if (name in setOf(
            "validateSigningRelease",
            "assembleRelease",
            "bundleRelease",
            "installRelease",
            "packageRelease",
            "packageReleaseBundle",
            "signReleaseBundle",
        )
    ) {
        dependsOn(validatePublishableReleaseSigning)
        dependsOn(validatePublisherIdentity)
    }
}

// JVM tests read these files from the source tree rather than from their classpath. Undeclared,
// a change to one of them alone left the test task up to date, or took its earlier passing result
// from the build cache, so a broken manifest or asset was reported as tested and passing.
tasks.withType<Test>().configureEach {
    val module = layout.projectDirectory
    val root = rootProject.layout.projectDirectory
    inputs.file(module.file("src/main/AndroidManifest.xml"))
        .withPropertyName("sourceManifest").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(module.dir("src/main/assets"))
        .withPropertyName("sourceAssets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(module.dir("src/main/java"))
        .withPropertyName("sourceCode").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(module.dir("src/androidTest/assets"))
        .withPropertyName("deviceTestAssets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(root.file("docs/privacy-policy.html"))
        .withPropertyName("webPrivacyPolicy").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(root.file("third_party/runtime-artifacts.json"))
        .withPropertyName("runtimeInventory").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(root.dir("third_party/libadb/src/main/assets"))
        .withPropertyName("libadbNotices").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // ADB client stack: RSA/TLS transport, A_STLS upgrade and SPAKE2 wireless-debugging pairing.
    implementation(project(":libadb"))
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // Test-only: the native SPAKE2 library the app replaced, to check the two agree.
    androidTestImplementation(libs.spake2.android)
}
