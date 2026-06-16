package networking

// iOS has no Android build types; default to production. For local iOS development, point this at
// your host (e.g. "http://localhost:60000"). The emulator-only 10.0.2.2 alias does not apply here.
actual val apiBaseUrl: String = "https://api.duckcount.app"
