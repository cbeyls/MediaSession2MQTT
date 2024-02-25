plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "be.digitalia.mediasession2mqtt"
    compileSdk = 34

    defaultConfig {
        applicationId = "be.digitalia.mediasession2mqtt"
        minSdk = 22
        targetSdk = 34
        versionCode = 2100100
        versionName = "1.0.0"

        resourceConfigurations += listOf("en")
        vectorDrawables {
            useSupportLibrary = true
        }
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
    val daggerVersion = "2.50"
    val kmqttVersion = "0.4.5"

    implementation("com.google.dagger:dagger:$daggerVersion")
    ksp("com.google.dagger:dagger-compiler:$daggerVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.github.davidepianca98:kmqtt-common-jvm:$kmqttVersion")
    implementation("io.github.davidepianca98:kmqtt-client-jvm:$kmqttVersion")
}