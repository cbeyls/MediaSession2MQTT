plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "be.digitalia.mediasession2mqtt"
    compileSdk = 34

    defaultConfig {
        applicationId = "be.digitalia.mediasession2mqtt"
        minSdk = 22
        targetSdk = 34
        versionCode = 2100110
        versionName = "1.1.0"

        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                "proguard-defaults.txt",
                "proguard-rules.pro"
            )

            kotlinOptions {
                freeCompilerArgs = listOf(
                    "-Xno-param-assertions",
                    "-Xno-call-assertions",
                    "-Xno-receiver-assertions"
                )
            }

            packaging {
                resources {
                    excludes += listOf(
                        "DebugProbesKt.bin",
                        "kotlin-tooling-metadata.json",
                        "kotlin/**",
                        "META-INF/*.kotlin_module",
                        "META-INF/*.version"
                    )
                }
                vcsInfo.include = false
            }
        }
    }
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kmqtt.common)
    implementation(libs.kmqtt.client)
}