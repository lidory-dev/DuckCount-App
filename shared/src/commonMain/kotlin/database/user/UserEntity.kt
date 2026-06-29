package database.user

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class UserEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val username: String,
    val fullName: String,
    // TODO: val profilePicture: String,
    val isDeleted: Boolean = false,
)
