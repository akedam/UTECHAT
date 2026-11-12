import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val storageEmulatorHost = providers.gradleProperty("utechatStorageEmulatorHost").orElse("10.0.2.2").get()
val storageEmulatorPort = providers.gradleProperty("utechatStorageEmulatorPort").orElse("19199").get().toInt()
val authEmulatorPort = providers.gradleProperty("utechatAuthEmulatorPort").orElse("19099").get().toInt()
val useStorageEmulator = providers.gradleProperty("utechatUseStorageEmulator").orElse("true").get().toBooleanStrict()
// Local public client settings; the App Certificate is never read by Android builds.
val agoraLocal = Properties().apply {
    val settings = rootProject.file("agora.local.properties")
    if (settings.isFile) settings.inputStream().use { load(it) }
}
val agoraAppId = providers.gradleProperty("utechatAgoraAppId")
    .orElse(agoraLocal.getProperty("utechatAgoraAppId", "")).get()
val agoraTokenServerUrl = providers.gradleProperty("utechatAgoraTokenServerUrl")
    .orElse(agoraLocal.getProperty("utechatAgoraTokenServerUrl", "")).get()
val useBackendMedia = providers.gradleProperty("utechatUseBackendMedia")
    .orElse(agoraLocal.getProperty("utechatUseBackendMedia", "false")).get().toBooleanStrict()
val testerSigning = Properties().apply {
    val settings = rootProject.file("tester-signing.local.properties")
    if (settings.isFile) settings.inputStream().use { load(it) }
}

// Apply only when the developer has supplied their own Firebase app configuration.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.utechat"
    buildFeatures { buildConfig = true }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.utechat"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "STORAGE_EMULATOR_HOST", "\"${storageEmulatorHost.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("int", "STORAGE_EMULATOR_PORT", storageEmulatorPort.toString())
        buildConfigField("int", "AUTH_EMULATOR_PORT", authEmulatorPort.toString())
        buildConfigField("String", "AGORA_APP_ID", "\"${agoraAppId.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "AGORA_TOKEN_SERVER_URL", "\"${agoraTokenServerUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "USE_BACKEND_MEDIA", useBackendMedia.toString())
    }

    signingConfigs {
        if (testerSigning.getProperty("storeFile") != null) {
            create("tester") {
                storeFile = rootProject.file(testerSigning.getProperty("storeFile"))
                storePassword = testerSigning.getProperty("storePassword")
                keyAlias = testerSigning.getProperty("keyAlias")
                keyPassword = testerSigning.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "USE_STORAGE_EMULATOR", useStorageEmulator.toString())
        }
        release {
            if (testerSigning.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("tester")
            // Never ship a release APK pointed at the developer's local emulator.
            buildConfigField("boolean", "USE_STORAGE_EMULATOR", "false")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    // Agora ships large native binaries per ABI. Keep Emulator and modern phone
    // targets while producing installable, architecture-specific APKs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation(libs.firebase.storage)
    implementation(libs.agora.rtc)
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.fragment)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.viewpager2)
    implementation(libs.glide)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
