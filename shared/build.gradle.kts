import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)

    // Room
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)

    // KSafe
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            // SQLCipher
            implementation("net.zetetic:sqlcipher-android:4.14.0@aar")

            // Koin
            implementation(libs.koin.android)

            // Ktor engine
            implementation(libs.ktor.client.cio)
        }
        commonMain.dependencies {
            // Lib-DuckCount
            implementation(project(":lib-duckcount"))

            // Room
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)

            // Koin
            implementation(libs.koin.core)

            // KSafe
            implementation(libs.ksafe)
            implementation(libs.ksafe.compose)

            // Ktor
            implementation(libs.bundles.ktor)
        }
        iosMain.dependencies {
            // Ktor engine
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "app.lidory.duckcount.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // API_BASE_URL is read in androidMain via BuildConfig (see networking/ApiConfig.android.kt).
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:60000\"")
        }
        getByName("release") {
            buildConfigField("String", "API_BASE_URL", "\"https://api.duckcount.app\"")
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}
