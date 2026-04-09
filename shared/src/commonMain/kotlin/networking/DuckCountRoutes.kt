package networking

object DuckCountRoutes {

    const val HOST = "10.0.2.2:60000"
    private const val BASE_URL = "http://$HOST"

    object Auth {
        private const val AUTH_BASE_URL = "$BASE_URL/auth"

        const val REGISTRATIONS = "$AUTH_BASE_URL/registrations"

        const val SESSIONS = "$AUTH_BASE_URL/sessions"

        const val TOKEN = "$AUTH_BASE_URL/token"
    }

    object Vault {
        const val VAULT_BASE_URL = "$BASE_URL/vault"
    }
}
