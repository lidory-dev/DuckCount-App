package networking

import app.lidory.duckcount.shared.BuildConfig

// The same number as the app's versionCode: see buildConfigField("APP_BUILD", …) in shared/build.gradle.kts.
actual val clientBuild: String = "android/${BuildConfig.APP_BUILD}"
