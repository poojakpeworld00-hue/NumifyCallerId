import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.googleservices)
    alias(libs.plugins.firebase.crashlytics)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** local.properties first (developer machines), then env var (CI). Never a default. */
fun secret(propKey: String, envKey: String): String? =
    (localProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val lhApiKey: String = localProps.getProperty("lighthouse.apiKey", "")
val lhBaseUrl: String = localProps.getProperty("lighthouse.baseUrl", "")

// Contact-saver API (number lookup + contact upload). Same treatment as the
// LightHouse key: kept out of source, XOR-obfuscated into BuildConfig, decoded at
// runtime by SecretDecoder. Sent as the x-api-key header, never as a query
// parameter — see docs/credentials.md.
//
// This replaces the old callerid.kpeworld.com credentials (lookup.apiId /
// apiHash / apiToken), which are no longer read by anything and can be deleted
// from local.properties.
val contactsApiKey: String = localProps.getProperty("contactsaver.apiKey", "")

// --- Release signing -------------------------------------------------------
// Credentials live in local.properties (gitignored) or CI env vars — never in
// this file. See docs/release-signing.md. When they're absent the release
// build stays unsigned rather than failing, so a fresh clone still builds.
// rootProject.file() returns an absolute path unchanged, so this accepts either
// an absolute path or one relative to the repo root.
val releaseStoreFile = secret("release.storeFile", "NUMIFY_RELEASE_STOREFILE")
    ?.let { rootProject.file(it) }
val releaseStorePassword = secret("release.storePassword", "NUMIFY_RELEASE_STOREPASSWORD")
val releaseKeyAlias = secret("release.keyAlias", "NUMIFY_RELEASE_KEYALIAS")
val releaseKeyPassword = secret("release.keyPassword", "NUMIFY_RELEASE_KEYPASSWORD")

val canSignRelease: Boolean =
    releaseStoreFile?.exists() == true &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

fun xorByteArrayLiteral(value: String, key: Int = 0x5A): String {
    if (value.isEmpty()) return "new byte[]{}"
    val parts = value.toByteArray(Charsets.UTF_8)
        .map { (it.toInt() xor key) and 0xFF }
        .map { if (it >= 0x80) it - 0x100 else it }   // Java byte is signed
        .joinToString(",")
    return "new byte[]{$parts}"
}

android {
    namespace = "com.numify.callerid.lookup"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.contacts.callerid.number.lookup"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true

        // LightHouse credentials → obfuscated BuildConfig byte[] (decoded at runtime
        // by SecretDecoder.s). buildConfig = true is enabled below.
        buildConfigField("byte[]", "LH_API_KEY", xorByteArrayLiteral(lhApiKey))
        buildConfigField("byte[]", "LH_BASE_URL", xorByteArrayLiteral(lhBaseUrl))

        // Contact-saver API key — same mechanism (see CredentialProvider.kt).
        buildConfigField("byte[]", "CONTACTS_API_KEY", xorByteArrayLiteral(contactsApiKey))
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 (JAR signing) is only needed below API 24, and minSdk is 24 —
                // the exact level APK Signature Scheme v2 shipped in. v2+v3 cover
                // the whole supported range, so v1 would be dead weight.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            // Resource shrinking left off — enable only after verifying a release build.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            // Resource shrinking left off — enable only after verifying a release build.
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(11)
}

// An unsigned release APK/AAB is useless and easy to produce by accident — say so
// loudly at configuration time, and name the first thing that's actually missing.
if (!canSignRelease) {
    val reason = when {
        releaseStoreFile == null -> "release.storeFile is not set"
        !releaseStoreFile.exists() -> "keystore not found at ${releaseStoreFile.absolutePath}"
        releaseStorePassword == null -> "release.storePassword is not set"
        releaseKeyAlias == null -> "release.keyAlias is not set"
        else -> "release.keyPassword is not set"
    }
    logger.warn("WARNING: release signing is not configured ($reason) — release builds will be UNSIGNED. See docs/release-signing.md")
}

// Empty credentials compile fine and then fail at runtime with a 401 that looks
// like a server problem — name the real cause here instead.
listOf(
    "contactsaver.apiKey" to contactsApiKey,
    "lighthouse.apiKey" to lhApiKey,
    "lighthouse.baseUrl" to lhBaseUrl,
).filter { (_, value) -> value.isBlank() }.forEach { (key, _) ->
    logger.warn("WARNING: $key is missing from local.properties — the feature it powers will fail at runtime. See docs/credentials.md")
}

base {
    val appName = "Contacts"
    val formattedDate: String =
        SimpleDateFormat("MMM.dd.yyyy", Locale.getDefault()).format(Date())
    val config = android.defaultConfig
    archivesName.set(
        "${appName}_${config.applicationId}_v${config.versionName}(${config.versionCode})_$formattedDate"
    )
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    implementation(libs.glide)
    implementation(libs.intuit.sdp)
    implementation(libs.intuit.ssp)
    implementation(libs.lottie)

    annotationProcessor(libs.glide.compiler)
    implementation(libs.shimmer)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Chucker — on-device HTTP(S) inspector. Real library in debug; no-op stub in
    // release so it ships nothing (no UI, no capture, zero overhead) to users.
    debugImplementation(libs.chucker)
    releaseImplementation(libs.chucker.noop)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    implementation(libs.gms.play.services.ads)
    implementation(libs.firebase.config)
    implementation(libs.installreferrer)
    implementation(libs.firebase.analytics)
    implementation(libs.google.firebase.crashlytics)
    implementation(libs.facebook.android.sdk)
    implementation(libs.audience.network.sdk)
    implementation(libs.dexter)
    implementation(libs.app.update)
    implementation(libs.app.update.ktx)
    implementation(libs.play.review)
    implementation(libs.play.review.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.multidex)
    implementation(libs.libphonenumber)
    implementation(libs.libphonenumber.geocoder)
    implementation(libs.libphonenumber.carrier)

    // LightHouse push SDK (replaces OneSignal).
    implementation(libs.lighthouse)
    implementation(libs.lighthouse.extended)
}
