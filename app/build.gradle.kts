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
val hceBoxVersionCode = 1_0_0
val hceBoxVersionName = "1.0.0"
val apkBuildDate = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

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
    implementation("com.hcebox:reader-protocol:0.1.0")
    implementation("com.hcebox:card-reader-driver-api:1.0.0-SNAPSHOT")
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
}
