plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "be.digitalia.mediasession2mqtt"
    compileSdk = 37

    defaultConfig {
        applicationId = "be.digitalia.mediasession2mqtt"
        minSdk = 22
        targetSdk = 37
        versionCode = 2200115
        versionName = "1.1.5"

        androidResources.localeFilters += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                "proguard-defaults.txt",
                "proguard-rules.pro"
            )

            packaging {
                resources {
                    excludes += listOf(
                        "DebugProbesKt.bin",
                        "kotlin-tooling-metadata.json",
                        "kotlin/**",
                        "META-INF/*.version",
                        "META-INF/versions/**",
                        "META-INF/NOTICE.md"
                    )
                }
                vcsInfo.include = false
            }
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kmqtt.common)
    implementation(libs.kmqtt.client)
}