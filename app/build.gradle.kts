plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airmirror.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airmirror.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 20
        versionName = "1.19"
    }

    // Two APKs from the same code: one for old TV boxes, one for current Android.
    flavorDimensions += "android"
    productFlavors {
        create("android5") {
            dimension = "android"
            minSdk = 21 // Android 5.0 Lollipop and newer
            versionNameSuffix = "-android5"
        }
        create("android8") {
            dimension = "android"
            minSdk = 26 // Android 8.0 Oreo and newer
            // Higher code so it replaces the android5 build on a newer device.
            versionCode = 1000 + (defaultConfig.versionCode ?: 0)
            versionNameSuffix = "-android8"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK installs directly on a TV box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/native-image/**",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    implementation(project(":airplay"))
    implementation("uk.uuid.slf4j:slf4j-android:2.0.16-0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
