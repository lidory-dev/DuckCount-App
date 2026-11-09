import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)

    alias(libs.plugins.jetbrains.kotlin.serialization)

    alias(libs.plugins.aboutLibraries)
}

// `-Pduckcount.fdroid` builds what F-Droid publishes, which differs only in where the update
// screen sends a phone with no app store.
val isFdroidBuild = providers.gradleProperty("duckcount.fdroid").isPresent

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.core.splashscreen)

            // android-ui
            implementation(project(":android-ui"))

            // Navigation
            implementation(libs.androidx.navigation3.ui)
            implementation(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            implementation(libs.androidx.material3.adaptive.navigation3)
            implementation(libs.kotlinx.serialization.core)

            // Dependency injection
            api(libs.koin.core)
            implementation(libs.koin.android)
            implementation(libs.koin.androidx.compose)

            // Dates, which the mappers hand from :shared to the feature modules
            implementation(libs.kotlinx.datetime)

            // Push
            implementation(libs.unifiedpush.connector)
            implementation(libs.unifiedpush.fcm.distributor)
            implementation(libs.androidx.work.runtime)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(projects.shared)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)

            // What the client encodes request bodies with, which the app itself only reaches
            // through :shared
            implementation(libs.ktor.serialization.kotlinx.json)

            // What the client reads answers with, for the answers a test hands it
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
        }
    }
}

android {
    namespace = "app.lidory.duckcount"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    // What strips the native libraries: without it installed, they ship with their symbols.
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        applicationId = "app.lidory.duckcount"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.android.versionCode.get().toInt()
        versionName = "1.1.0"

        // Where the update screen sends a phone with no app store, see UpdateRequiredScreen.
        buildConfigField(
            "String",
            "STORE_PAGE_PREFIX",
            if (isFdroidBuild) {
                "\"https://f-droid.org/packages/\""
            } else {
                "\"https://play.google.com/store/apps/details?id=\""
            },
        )
    }
    buildFeatures {
        buildConfig = true
    }
    // AGP would otherwise sign a list of the dependencies into the APK, encrypted to Google.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // So that what a test runs can log: android.util.Log's stub would throw otherwise.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    debugImplementation(libs.compose.ui.tooling)

    // Paging (mapping PagingData between :shared domain and feature models)
    implementation(libs.androidx.room.paging.compose)

    implementation(project(":shared"))
    implementation(project(":lib-duckcount"))
    implementation(project(":feature:expenseList"))
    implementation(project(":feature:authentication"))
    implementation(project(":feature:contactList"))
    implementation(project(":feature:groupList"))
    implementation(project(":feature:settings"))
}

