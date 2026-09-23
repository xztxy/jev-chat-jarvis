import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads an optional properties file
// (storeFile / storePassword / keyAlias / keyPassword) specified via JEV_KEYSTORE_PROPS.
val releaseProps = Properties().apply {
    val propPath = System.getenv("JEV_KEYSTORE_PROPS")
    if (!propPath.isNullOrBlank()) {
        val f = File(propPath)
        if (f.exists()) {
            FileInputStream(f).use { stream -> load(stream) }
        }
    }
}

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    // Fixed signing key so every build carries the SAME signature and new versions
    // install over old ones. Sources, in order:
    //   1. JEV_KEYSTORE_PROPS (external properties file, original mechanism)
    //   2. ANDROID_KEYSTORE_BASE64 (CI secret, decoded to a temp file)
    //   3. keystore/jev-release.jks committed in this repo
    // Source 3 is a convenience for this personal fork only: the key is public, so
    // re-key through source 2 before distributing to anyone else.
    val ciKeystore = File(System.getenv("RUNNER_TEMP") ?: "/tmp", "ci-release.jks")
    val keystoreB64 = System.getenv("ANDROID_KEYSTORE_BASE64")
    if (!keystoreB64.isNullOrBlank()) {
        ciKeystore.writeBytes(Base64.getDecoder().decode(keystoreB64.trim()))
    }

    defaultConfig {
        applicationId = "com.jev.probe"
        minSdk = 30
        targetSdk = 35
        versionCode = 8
        versionName = "1.4.3-custom"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        val repoKeystore = rootProject.file("keystore/jev-release.jks")
        val store = when {
            releaseProps.isNotEmpty() -> file(releaseProps.getProperty("storeFile"))
            !keystoreB64.isNullOrBlank() && ciKeystore.exists() -> ciKeystore
            repoKeystore.exists() -> repoKeystore
            else -> null
        }
        if (store != null) {
            create("release") {
                storeFile = store
                storePassword = releaseProps.getProperty("storePassword") ?: "jevrelease2026"
                keyAlias = releaseProps.getProperty("keyAlias") ?: "jev"
                keyPassword = releaseProps.getProperty("keyPassword") ?: "jevrelease2026"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
