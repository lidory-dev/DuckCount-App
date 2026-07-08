package database.data.remote

class GroupDto(
    val uuid: String,
    val title: EncryptedFieldDto,
    val description: EncryptedFieldDto,
    val members: List<GroupMemberDto>,
)