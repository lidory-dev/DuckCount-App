package database.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import database.domain.Currency

@Entity
class GroupEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val title: String,
    val description: String,
    val currency: Currency,
)
