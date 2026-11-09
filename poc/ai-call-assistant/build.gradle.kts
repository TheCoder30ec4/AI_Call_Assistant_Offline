import java.util.Properties

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // LiteRT-LM is compiled with Kotlin 2.4, newer than the version built into this Android plugin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1"
}

// Release signing comes from keystore.properties, which is never committed (see .gitignore).
val keystore = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "app.offlinevoice.callprobe"
    compileSdk = 36

    defaultConfig {
        // Kept from the first builds so existing installs keep their Shizuku grant and app ops.
        applicationId = "app.offlinevoice.callprobe"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    signingConfigs {
        if (keystore.isNotEmpty()) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // ponytail: no R8 shrinking. Shizuku's AIDL, sherpa-onnx and LiteRT-LM all use JNI and
            // reflection; add keep rules and turn it on once there's a way to test every call path.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(files("sherpa-onnx-2.0.1.aar"))
    testImplementation("junit:junit:4.13.2")
}
