import java.security.KeyStore
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Updates must install over the app already on the phone ----
//
// 1. Same signing key. Android only installs an update signed with the same key as the installed
//    app. Release signing reads android/keystore.properties (never committed) or the environment
//    (GitHub secrets). With REQUIRE_RELEASE_KEY=1 (set on GitHub) a release build refuses to run
//    without the key, or with any key other than the app's own (checked by its fingerprint).
// 2. Higher version code. Android refuses an "update" whose versionCode is not higher. It is
//    generated from the build time (minutes since 2025-01-01), so every later build is higher,
//    whatever branch or machine builds it. VERSION_CODE in the environment overrides it.

/** SHA-256 of the Video Studio release certificate (CN=Video Studio). Every update must match. */
val releaseCertSha256 = "2b18af1117d2e064e5c52e4c4272cb9471658ec4ea3804a4e46432c4f60d6c14"

val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    keyProps.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_'))?.takeIf { it.isNotBlank() }

val requireReleaseKey = System.getenv("REQUIRE_RELEASE_KEY") == "1"

/** Fails the build when the key is missing or is not the app's own key. */
fun checkReleaseKey() {
    val path = signingValue("store.file")
    if (path == null) {
        if (requireReleaseKey) throw GradleException(
            "No release signing key. Add the RELEASE_KEYSTORE_BASE64, RELEASE_KEYSTORE_PASSWORD and RELEASE_KEY_ALIAS " +
                "repository secrets: an APK signed with another key will not install over the app."
        )
        return
    }
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    file(path).inputStream().use { ks.load(it, signingValue("store.password")?.toCharArray()) }
    val alias = signingValue("key.alias") ?: throw GradleException("key.alias is missing")
    val cert = ks.getCertificate(alias) ?: throw GradleException("No key named '$alias' in the keystore")
    val sha = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
    if (sha != releaseCertSha256) throw GradleException(
        "This keystore is not the Video Studio release key (certificate $sha). Updates signed with it would not install."
    )
}

val autoVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: Duration.between(Instant.parse("2025-01-01T00:00:00Z"), Instant.now()).toMinutes().toInt()

android {
    namespace = "com.videostudio.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.videostudio.app"
        minSdk = 26
        targetSdk = 35
        // Always higher than the last build (1.2 was 3), see autoVersionCode above.
        versionCode = maxOf(4, autoVersionCode)
        versionName = "2.4"
    }

    signingConfigs {
        create("release") {
            val store = signingValue("store.file")
            if (store != null) {
                storeFile = file(store)
                storePassword = signingValue("store.password")
                keyAlias = signingValue("key.alias")
                keyPassword = signingValue("key.password")
            }
        }
    }

    buildTypes {
        release {
            // Not shrunk, like the original release: keeps every class the app might need at runtime.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            checkReleaseKey()
            signingConfig = if (signingValue("store.file") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric downloads its Android runtime itself; follow the same opt-in mirror.
                if (System.getenv("CENTRAL_MIRROR") != null) {
                    it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                }
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Animated emoji and motion graphics (Lottie, Apache-2.0).
    implementation("com.airbnb.android:lottie:6.6.2")
    // On-device subject cut-out for the "pop-out" photo look (free, runs on the phone).
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
