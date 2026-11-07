package networking

// dev server (via adb reverse) on debug, production on release, see apiBaseUrl +
// shared/build.gradle.kts.
private val baseUrl = apiBaseUrl

object DuckCountRoutes {

    object Auth {
        private val authBaseUrl = "$baseUrl/auth"

        val REGISTRATIONS = "$authBaseUrl/registrations"

        /** `POST` hands out the challenge the attestation starting a registration goes over. */
        val REGISTRATION_CHALLENGES = "$authBaseUrl/registrations/challenges"

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
         * `GET` lists the versions of the information of one group the server keeps, the current
         * one and those its edits replaced, newest first, any current member.
         */
        val HISTORY = "$GROUP_BASE_URL/:group_uuid/history"

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

        /**
         * `DELETE` removes from one group members who departed from it and whom no Commit removed
         * yet, any current member.
         */
        val DEPARTED = "$GROUP_BASE_URL/:group_uuid/departed"

        /**
         * `POST` replaces the Commit every member's device refused in one group, and every one
         * stored after it, with the owner's, its owner alone.
         */
        val COMMIT_REPLACEMENT = "$GROUP_BASE_URL/:group_uuid/commits/replacement"

        /**
         * `POST` stores the Commit from outside one group with which the caller rejoins it after a
         * log-out, the resync of their own leaf, any current member.
         */
        val COMMIT_REJOIN = "$GROUP_BASE_URL/:group_uuid/commits/rejoin"

        /**
         * `PUT` asks the other current members of one group for a GroupInfo to rejoin it from, any
         * current member.
         */
        val REJOIN_REQUEST = "$GROUP_BASE_URL/:group_uuid/rejoin-request"

        /**
         * `PUT` publishes the caller's GroupInfo of the epoch one group is in, for a member who asked
         * for one, any current member.
         */
        val GROUP_INFO = "$GROUP_BASE_URL/:group_uuid/group-info"

        /** `GET` lists who published a GroupInfo of the epoch one group is in, any current member. */
        val GROUP_INFOS = "$GROUP_BASE_URL/:group_uuid/group-infos"

        /** `GET` returns the GroupInfo one member published of the epoch one group is in, any current member. */
        val PUBLISHED_GROUP_INFO = "$GROUP_BASE_URL/:group_uuid/group-infos/:publisher_uuid"

        /**
         * `GET` lists the candidates for the links of the chain back through the keys of one group,
         * each the key of a generation sealed under the next, any current member.
         */
        val KEY_LINKS = "$GROUP_BASE_URL/:group_uuid/key-links"

        /** `PUT` publishes the caller's own link at one generation of that chain, any current member. */
        val KEY_LINK = "$GROUP_BASE_URL/:group_uuid/key-links/:generation"

        /**
         * The caller's own copy of the key of one group: `PUT` stores it, any current member,
         * `DELETE` drops it. `GET /groups` lists it.
         */
        val KEY_COPY = "$GROUP_BASE_URL/:group_uuid/key-copy"

        /** `DELETE` takes the caller out of one group, any member but its owner. */
        val MEMBERSHIP = "$GROUP_BASE_URL/:group_uuid/membership"

        /**
         * `GET` lists the expenses of one group, a page at a time, newest first, `POST` creates one,
         * any current member.
         */
        val EXPENSES = "$GROUP_BASE_URL/:group_uuid/expenses"

        /** What changed in the expenses of one group since a place in the caller's feed of them. */
        val EXPENSE_CHANGES = "$GROUP_BASE_URL/:group_uuid/expenses/changes"

        /** `PUT` replaces one expense of a group with its next version, an edit, any current member. */
        val EXPENSE = "$GROUP_BASE_URL/:group_uuid/expenses/:expense_uuid"

        /**
         * `PUT` deletes one expense of a group, or restores it, with its next deletion flag, which
         * is no edit, any current member. `GET` of the group's expenses lists it.
         */
        val EXPENSE_DELETION = "$GROUP_BASE_URL/:group_uuid/expenses/:expense_uuid/deletion"

        /** `GET` lists the versions of one expense of a group its updates replaced, newest first. */
        val EXPENSE_HISTORY = "$GROUP_BASE_URL/:group_uuid/expenses/:expense_uuid/history"
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
