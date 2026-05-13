package database.group

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
class GroupEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val description: String,
    val title: String,
)
