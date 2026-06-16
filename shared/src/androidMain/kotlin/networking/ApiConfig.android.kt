package networking

import app.lidory.duckcount.shared.BuildConfig

// Resolved per build type: see buildConfigField("API_BASE_URL", …) in shared/build.gradle.kts.
actual val apiBaseUrl: String = BuildConfig.API_BASE_URL
