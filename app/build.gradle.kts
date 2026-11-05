import java.util.Properties
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Missing configuration shows a setup screen; it never falls back to a demo project.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
val localBackendProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val explicitBackendUrl = providers.gradleProperty("backendBaseUrl")
    .orElse(providers.environmentVariable("BACKEND_BASE_URL")).orElse("").get().trimEnd('/')
// A public Web client override supports Firebase's explicit external-client safelist.
val googleWebClientId = providers.gradleProperty("googleWebClientId")
    .orElse(providers.environmentVariable("GOOGLE_WEB_CLIENT_ID")).orElse("").get().trim()
require(googleWebClientId.isBlank() || googleWebClientId.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))) {
    "googleWebClientId harus berupa OAuth Web client ID publik yang sudah diizinkan oleh provider Google Firebase."
}
// Signing remains local to the operator or CI; no credential is bundled in the APK.
val releaseSigningProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun releaseSigningValue(property: String, environment: String): String =
    providers.environmentVariable(environment).orElse(releaseSigningProperties.getProperty(property, "")).get()
val releaseStorePath = releaseSigningValue("storeFile", "JTP_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "JTP_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "JTP_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "JTP_RELEASE_KEY_PASSWORD")
val releaseSigningComplete = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it.isNotBlank() }
// Development identity travels with the private signing kit, never the laptop's default key.
val developmentStore = rootProject.file(".signing/development.keystore")
val expectedSigningCertificates = Properties().apply {
    rootProject.file("signing-certificates.properties").inputStream().use { load(it) }
}
fun validateSigningCertificate(store: File, password: String, alias: String, kind: String) {
    val certificate = try {
        KeyStore.getInstance(store, password.toCharArray()).getCertificate(alias)
            ?: throw GradleException("Alias sertifikat $kind tidak ditemukan.")
    } catch (error: Exception) {
        throw GradleException("Keystore $kind tidak dapat dibaca. Pulihkan paket signing proyek yang sama; periksa konfigurasi lokalnya.", error)
    }
    val actual = MessageDigest.getInstance("SHA-1").digest(certificate.encoded)
        .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    if (actual != expectedSigningCertificates.getProperty("$kind.sha1")) {
        throw GradleException("Sertifikat $kind berbeda dari identitas OAuth proyek. Build dihentikan agar laptop baru tidak menghasilkan APK dengan identitas yang salah. Pulihkan paket signing asli. SHA-1 yang ditemukan: $actual")
    }
}
fun checkedBackendUrl(value: String): String {
    require(value.none { it == '"' || it == '\\' || it == '\n' || it == '\r' }) { "backendBaseUrl tidak valid" }
    return value
}
android {
    namespace = "com.jayaguna.admin"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jayaguna.admin"
        minSdk = 26
        targetSdk = 36
        versionCode = 20
        versionName = "1.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Jayaguna Admin")
        resValue("string", "google_web_client_id", googleWebClientId)
        buildConfigField("String", "BACKEND_BASE_URL", "\"${checkedBackendUrl(explicitBackendUrl)}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        getByName("debug") {
            storeFile = developmentStore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseSigningComplete) create("companyRelease") {
            storeFile = rootProject.file(releaseStorePath)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Jayaguna Admin (Debug)")
            // Persist Android Studio Run configuration locally; never ship a
            // workstation's localhost URL as the release default.
            val debugBackendUrl = explicitBackendUrl.ifBlank { localBackendProperties.getProperty("backend.debugBaseUrl", "").trimEnd('/') }
            buildConfigField("String", "BACKEND_BASE_URL", "\"${checkedBackendUrl(debugBackendUrl)}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningComplete) signingConfig = signingConfigs.getByName("companyRelease")
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
val validateDevelopmentSigning by tasks.registering {
    group = "verification"
    description = "Requires the existing shared development certificate before packaging."
    doLast {
        if (!developmentStore.isFile) {
            throw GradleException("Sertifikat pengembangan proyek belum tersedia. Pulihkan folder .signing dari paket privat proyek sebelum menjalankan aplikasi. Jangan membuat debug key baru di laptop ini karena identitas OAuth harus tetap sama. Lihat docs/PORTABLE_BUILD.md.")
        }
        validateSigningCertificate(developmentStore, "android", "androiddebugkey", "development")
    }
}
tasks.matching { it.name in setOf("validateSigningDebug", "validateSigningDebugAndroidTest") }.configureEach {
    dependsOn(validateDevelopmentSigning)
}
val validateReleaseDistribution by tasks.registering {
    group = "verification"
    description = "Checks optional legacy API origin and local release signing configuration."
    doLast {
        val target = runCatching { URI(explicitBackendUrl) }.getOrNull()
        val host = target?.host?.removeSurrounding("[", "]")?.lowercase().orEmpty()
        val loopback = host in setOf("localhost", "0.0.0.0", "::", "::1", "0:0:0:0:0:0:0:1", "10.0.2.2") ||
            host.startsWith("127.") || host.endsWith(".localhost") || host.startsWith("::ffff:127.")
        if (explicitBackendUrl.isNotBlank() && (target == null || target.scheme?.lowercase() != "https" || host.isBlank() || loopback || target.userInfo != null ||
            target.rawQuery != null || target.rawFragment != null || target.path !in setOf("", "/") ||
            (target.port != -1 && target.port !in 1..65535))) {
            throw GradleException("Jika API lama diaktifkan, origin harus HTTPS dan dapat dijangkau HP. Upload Drive dan penyimpanan katalog native tidak memerlukan origin API ini.")
        }
        if (!releaseSigningComplete) {
            throw GradleException("Release memerlukan signing key organisasi. Salin keystore.properties.example ke keystore.properties lalu isi empat nilai, atau atur JTP_RELEASE_STORE_FILE, JTP_RELEASE_STORE_PASSWORD, JTP_RELEASE_KEY_ALIAS dan JTP_RELEASE_KEY_PASSWORD. Jangan membagikan file/rahasianya.")
        }
        if (!rootProject.file(releaseStorePath).isFile) {
            throw GradleException("File keystore release belum ditemukan. Periksa storeFile pada konfigurasi signing lokal.")
        }
        validateSigningCertificate(rootProject.file(releaseStorePath), releaseStorePassword, releaseKeyAlias, "release")
    }
}
// Actual task dependencies cover CLI and Android Studio APK/AAB generation,
// without blocking compilation or unit tests that do not distribute a release.
tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "bundleRelease", "assembleRelease") }.configureEach {
    dependsOn(validateReleaseDistribution)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-storage")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
