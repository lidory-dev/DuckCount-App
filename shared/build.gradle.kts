import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable

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

        // The tests of the database's queries run them on an in-memory database opened on the
        // system's SQLite, which the test executable alone links. The framework links none: the
        // app supplies SQLCipher, see database/SqlCipherDriver.kt.
        iosTarget.binaries.withType<TestExecutable>().configureEach {
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets {
        androidMain.dependencies {
            // SQLCipher
            implementation("net.zetetic:sqlcipher-android:4.16.0@aar")

            // Koin
            implementation(libs.koin.android)

            // Ktor engine
            implementation(libs.ktor.client.cio)

            // Paging
            implementation(libs.androidx.room.paging.runtime)
        }
        commonMain.dependencies {
            // Lib-DuckCount
            implementation(project(":lib-duckcount"))

            // Room
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.room.paging)

            // Paging
            implementation(libs.androidx.room.paging.compose)

            // Koin
            implementation(libs.koin.core)

            // Kotlinx DateTime
            implementation(libs.kotlinx.datetime)

            // KSafe
            implementation(libs.ksafe)
            implementation(libs.ksafe.compose)

            // Ktor
            implementation(libs.bundles.ktor)
        }
        iosMain.dependencies {
            // SQLite driver, run on the SQLCipher the app links: see database/SqlCipherDriver.kt
            implementation(libs.androidx.sqlite.framework)

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
        // Debug points at the *device's own* loopback, which `adb reverse tcp:60000 tcp:60000`
        // forwards to the dev server on the host. Unlike the emulator-only alias 10.0.2.2, this
        // resolves the same way on an emulator and on real hardware, and it leaves the dev API
        // bound to the host's loopback instead of the LAN. Re-run `adb reverse` after each
        // device reconnect.
        getByName("debug") {
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:60000\"")
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
