plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "com.yusuf.ceviri"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yusuf.ceviri"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Sabit imza: her guncelleme eskisinin uzerine kurulur. Anahtar depoda degil,
    // GitHub Secrets'tan (CEVIRI_KEYSTORE_B64 / CEVIRI_KEYSTORE_PASSWORD) gelir.
    val ksPath = System.getenv("CEVIRI_KEYSTORE")
    val ksPass = System.getenv("CEVIRI_KEYSTORE_PASSWORD")
    signingConfigs {
        create("release") {
            if (ksPath != null && ksPass != null) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = "ceviri"
                keyPassword = ksPass
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // espeak-ng-data icinde "_" ile baslayan klasorler / nokta dosyalari atlanmasin.
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    // Yalniz arm64 telefon hedefleniyor; iki kutuphanenin diger mimari kopyalari cakisiyor.
    packaging {
        jniLibs {
            excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation("androidx.core:core:1.13.1")
}
