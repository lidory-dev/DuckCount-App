// The libraries the iOS app ships, for its licences screen to list, as `:feature:settings` lists
// Android's: those the Shared framework links, read from Gradle, and SQLCipher, which the app links
// as a Swift package and `config/` describes. iOS only, the AboutLibraries plugin adding its list to
// the resources of any Android module it is applied to, which on `:shared` would reach the APK.
//
// Xcode's "Compile Kotlin Framework" phase runs `exportLibraryDefinitions` and copies the file into
// the app, see `iosApp/iosApp.xcodeproj`. The device's dependencies are the simulator's.

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.aboutLibraries)
}

kotlin {
    iosArm64()

    sourceSets {
        iosMain.dependencies {
            implementation(project(":shared"))
        }
    }
}

aboutLibraries {
    collect {
        configPath = file("config")

        // The plugin reads the JVM's classpaths alone unless told otherwise, and a native target's
        // is this one: the libraries the framework is linked from.
        all = true
        filterVariants.add("iosArm64CompileKlibraries")
    }

    export {
        outputFile = layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json")
    }
}
