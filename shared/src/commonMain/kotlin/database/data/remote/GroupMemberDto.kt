@file:Suppress("PropertyName")

package database.data.remote

data class GroupMemberDto(
    val user_uuid: String,
    val is_admin: Boolean,
)
