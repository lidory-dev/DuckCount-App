package networking

// dev server (via adb reverse) on debug, production on release, see apiBaseUrl +
// shared/build.gradle.kts.
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

        /** One part of the vault: `GET` its entry, `PUT` it, `DELETE` it. */
        val VAULT_PART = "$VAULT_BASE_URL/:entry_key"
    }

    object User {
        private val USER_BASE_URL = "$baseUrl/users"

        val USERNAME = "$USER_BASE_URL/:user_uuid/username"

        val PROFILE = "$USER_BASE_URL/:user_uuid/profile"

        /** The caller's MLS key packages. `PUT` to publish them. */
        val KEY_PACKAGE = "$USER_BASE_URL/:user_uuid/key-package"

        /**
         * The caller's account: `POST` starts the re-authentication deleting it takes, `PUT`
         * finishes it and deletes the account.
         */
        val DELETION = "$USER_BASE_URL/:user_uuid/deletion"

        /**
         * The caller's password: `POST` starts the two handshakes changing it takes, a login with
         * the current password and a registration of the new one, `PUT` finishes them and
         * changes it.
         */
        val PASSWORD = "$USER_BASE_URL/:user_uuid/password"
    }

    object Contact {
        private val CONTACT_BASE_URL = "$baseUrl/contacts"

        /**
         * The contact requests: `GET` lists the ones addressed to the caller, oldest
         * first, `POST` sends one.
         */
        val REQUESTS = "$CONTACT_BASE_URL/requests"

        /** One request addressed to the caller. `DELETE` refuses it. */
        val REQUEST = "$CONTACT_BASE_URL/requests/:request_uuid"

        /**
         * `POST` accepts a request addressed to the caller, `GET` says what became of one:
         * still pending, taken by the caller's acceptance, or gone otherwise. `DELETE` lets
         * the server forget the record of the caller's acceptance once settled.
         */
        val REQUEST_ACCEPTANCE = "$CONTACT_BASE_URL/requests/:request_uuid/acceptance"

        /**
         * `GET` resolves a discovery username digest to its holder and one of their key
         * packages, which it consumes.
         */
        val LOOKUP = "$CONTACT_BASE_URL/lookup/:username"
    }

    object Message {
        private val MESSAGE_BASE_URL = "$baseUrl/messages"

        /**
         * `GET` lists the direct messages waiting for the caller, oldest first, `POST`
         * sends one.
         */
        val MESSAGES = MESSAGE_BASE_URL

        /** `POST` acknowledges direct messages, which deletes them. */
        val ACKNOWLEDGEMENTS = "$MESSAGE_BASE_URL/acknowledgements"
    }

    object Group {
        private val GROUP_BASE_URL = "$baseUrl/groups"

        /** `GET` lists the groups the caller is a member of, `POST` creates one. */
        val GROUPS = GROUP_BASE_URL

        /** `PUT` edits the information of one group, its owner alone. */
        val GROUP = "$GROUP_BASE_URL/:group_uuid"

        /**
         * `GET` lists the messages waiting for the caller in one group, oldest first, `POST` posts
         * one to every other current member, any member.
         */
        val MESSAGES = "$GROUP_BASE_URL/:group_uuid/messages"

        /**
         * `POST` acknowledges messages of one group, which deletes each once every member it
         * waited for has.
         */
        val ACKNOWLEDGEMENTS = "$GROUP_BASE_URL/:group_uuid/messages/acknowledgements"

        /** `POST` adds members to one group, `DELETE` removes some, its creator alone. */
        val MEMBERS = "$GROUP_BASE_URL/:group_uuid/members"

        /** `PUT` rotates the key of one group after a removal, its creator alone. */
        val KEY = "$GROUP_BASE_URL/:group_uuid/key"

        /**
         * The caller's own copy of the key of one group: `PUT` stores it, any current member,
         * `DELETE` drops it. `GET /groups` lists it.
         */
        val KEY_COPY = "$GROUP_BASE_URL/:group_uuid/key-copy"

        /** `DELETE` takes the caller out of one group, any member but its owner. */
        val MEMBERSHIP = "$GROUP_BASE_URL/:group_uuid/membership"
    }

    object Push {
        private val PUSH_BASE_URL = "$baseUrl/push"

        /** `GET` returns what a client needs before it can subscribe. */
        val PARAMETERS = "$PUSH_BASE_URL/parameters"

        /**
         * `PUT` registers where to wake this device, replacing any earlier
         * registration, `DELETE` forgets it.
         */
        val SUBSCRIPTION = "$PUSH_BASE_URL/subscription"
    }

    object Expense {
        private val EXPENSE_BASE_URL = "$baseUrl/expenses"

        val EXPENSES = EXPENSE_BASE_URL

        val EXPENSE_BY_UUID = "$EXPENSE_BASE_URL/:expense_uuid"

        /** What changed in the caller's expenses since a place in their feed. */
        val EXPENSE_CHANGES = "$EXPENSE_BASE_URL/changes"

        /** The caller's own copy of an expense's key. `PUT` only: `GET /expenses` lists it. */
        val EXPENSE_KEY_COPY = "$EXPENSE_BASE_URL/:expense_uuid/key-copy"

        /** The versions an expense had before each of its edits. */
        val EXPENSE_HISTORY = "$EXPENSE_BASE_URL/:expense_uuid/history"

        /** Whether an expense was paid back, which is no edit. `PUT` only: `GET /expenses` lists it. */
        val EXPENSE_SETTLED = "$EXPENSE_BASE_URL/:expense_uuid/settled"
    }
}
