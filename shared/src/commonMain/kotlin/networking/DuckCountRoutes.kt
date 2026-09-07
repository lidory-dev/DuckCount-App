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
        private val VAULT_BASE_URL = "$baseUrl/vault"

        /** The whole vault: `GET` to sync it, `PATCH` to write several parts at once. */
        val VAULT = VAULT_BASE_URL

        /** One part of the vault: `GET` its items, `PUT` one, `DELETE` them all. */
        val VAULT_PART = "$VAULT_BASE_URL/:entry_key"

        /** One item of a part. `DELETE` only: `PUT` carries the item id in its body. */
        val VAULT_ITEM = "$VAULT_BASE_URL/:entry_key/:item_id"
    }

    object User {
        private val USER_BASE_URL = "$baseUrl/users"

        val USERNAME = "$USER_BASE_URL/:user_uuid/username"

        val PROFILE = "$USER_BASE_URL/:user_uuid/profile"
    }

    object Expense {
        private val EXPENSE_BASE_URL = "$baseUrl/expenses"

        val EXPENSES = EXPENSE_BASE_URL

        val EXPENSE_BY_UUID = "$EXPENSE_BASE_URL/:expense_uuid"
    }
}
