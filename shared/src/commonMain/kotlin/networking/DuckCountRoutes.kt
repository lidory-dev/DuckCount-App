package networking

// dev/emulator on debug, production on release, see apiBaseUrl + shared/build.gradle.kts.
private val baseUrl = apiBaseUrl

object DuckCountRoutes {

    object Auth {
        private val authBaseUrl = "$baseUrl/auth"

        val REGISTRATIONS = "$authBaseUrl/registrations"

        val SESSIONS = "$authBaseUrl/sessions"

        val TOKEN = "$authBaseUrl/token"
    }

    object Vault {
        val VAULT_BASE_URL = "$baseUrl/vault"
    }

    object Expense {
        private val EXPENSE_BASE_URL = "$baseUrl/expenses"

        val EXPENSES = EXPENSE_BASE_URL

        val EXPENSE_BY_UUID = "$EXPENSE_BASE_URL/:expense_uuid"
    }
}
