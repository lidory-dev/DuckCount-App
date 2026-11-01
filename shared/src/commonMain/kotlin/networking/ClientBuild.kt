package networking

/**
 * What every request to the DuckCount API names itself as: the platform, a slash and the build
 * number, `android/12` or `ios/12`, which the API compares to its minimum for that platform, see
 * [UpdateRequirement]. Per platform, because the two apps ship apart.
 *
 * On Android the number is the versionCode, read from `BuildConfig.APP_BUILD` (`android-versionCode`
 * in the version catalog, see `shared/build.gradle.kts`). On iOS it is the app's `CFBundleVersion`
 * (`CURRENT_PROJECT_VERSION` in `iosApp/Configuration/Config.xcconfig`). Integers that only go up,
 * unlike either version name.
 */
expect val clientBuild: String
