package app.lidory.duckcount

import uniffi.lib_duckcount.maxGroupMembers as maxGroupMembersUniffi

object GroupLimits {
    const val MAX_TITLE_LENGTH = 50

    const val MAX_DESCRIPTION_LENGTH = 2000

    /**
     * The most members a group may hold, its creator included. Read from the library, so the
     * form counts against the very limit the library refuses a group past rather than repeating
     * the number: the server refuses a longer list too, and a joiner a Welcome to a larger group.
     */
    val MAX_MEMBERS: Int = maxGroupMembersUniffi().toInt()
}
