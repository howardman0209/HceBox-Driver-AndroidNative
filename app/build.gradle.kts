import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Match ACS release identity and artifact naming without storing signing secrets here.
val hceBoxReleaseStoreFile = providers.gradleProperty("HCEBOX_RELEASE_STORE_FILE").orNull
val hceBoxReleaseStorePassword = providers.gradleProperty("HCEBOX_RELEASE_STORE_PASSWORD").orNull
val hceBoxReleaseKeyAlias = providers.gradleProperty("HCEBOX_RELEASE_KEY_ALIAS").orNull
val hceBoxReleaseKeyPassword = providers.gradleProperty("HCEBOX_RELEASE_KEY_PASSWORD").orNull
val hceBoxApplicationId = "com.hcebox.driver.androidnative"
val hceBoxVersionName = "1.0.0"
// Encode semantic versions monotonically; minor and patch each occupy two digits.
val hceBoxVersionParts = hceBoxVersionName.split('.').map(String::toInt)
require(hceBoxVersionParts.size == 3 && hceBoxVersionParts[0] >= 0 &&
    hceBoxVersionParts[1] in 0..99 && hceBoxVersionParts[2] in 0..99) {
    "Use major.minor.patch with minor/patch between 0 and 99"
}
val encodedVersionCode = hceBoxVersionParts[0].toLong() * 10000 +
    hceBoxVersionParts[1] * 100 + hceBoxVersionParts[2]
require(encodedVersionCode in 1..2_100_000_000) { "versionCode exceeds the Android range" }
val hceBoxVersionCode = encodedVersionCode.toInt()
val apkBuildDate: String? = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

android {
    namespace = "com.hcebox.driver.androidnative"
    compileSdk = 37
    defaultConfig {
        applicationId = hceBoxApplicationId
        minSdk = 26
        targetSdk = 37
        versionCode = hceBoxVersionCode
        versionName = hceBoxVersionName
        testInstrumentationRunner = "com.hcebox.driver.androidnative.DriverSmokeInstrumentation"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            storeFile = hceBoxReleaseStoreFile?.let { file(it) }
            storePassword = hceBoxReleaseStorePassword
            keyAlias = hceBoxReleaseKeyAlias
            keyPassword = hceBoxReleaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            versionNameSuffix = ".debug"
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
base {
    archivesName = "${hceBoxApplicationId}_${hceBoxVersionName}-${hceBoxVersionCode}-$apkBuildDate"
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.hcebox:remote-client:0.2.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.hcebox:reader-protocol:0.2.0")
    implementation("com.hcebox:card-reader-driver-api:1.0.0-SNAPSHOT")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
}
