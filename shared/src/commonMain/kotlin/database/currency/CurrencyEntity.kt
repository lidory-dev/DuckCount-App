package database.currency

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class CurrencyEntity(
    @PrimaryKey(autoGenerate = false) val uuid: String,
    val name: String,
    val symbol: String,
    val hasSubunits: Boolean,
)
