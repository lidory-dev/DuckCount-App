package networking

import platform.Foundation.NSBundle

// The app's own, set from CURRENT_PROJECT_VERSION in iosApp/Configuration/Config.xcconfig.
actual val clientBuild: String = "ios/${NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion")}"
