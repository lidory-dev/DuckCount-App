package networking

/**
 * Base URL of the DuckCount API for the current build.
 *
 * On Android it comes from the build type via `BuildConfig.API_BASE_URL` (debug → dev/emulator,
 * release → production, see `shared/build.gradle.kts`). Other platforms supply their own value.
 */
expect val apiBaseUrl: String
