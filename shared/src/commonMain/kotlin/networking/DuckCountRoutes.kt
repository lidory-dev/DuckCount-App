package networking

object DuckCountRoutes {
    private const val BASE_URL = "https://api.duckcount.app"

    object Auth {
        private const val AUTH_BASE_URL = "$BASE_URL/auth"

        const val REGISTRATIONS = "$AUTH_BASE_URL/registrations"

        const val SESSIONS = "$AUTH_BASE_URL/sessions"
    }
}
