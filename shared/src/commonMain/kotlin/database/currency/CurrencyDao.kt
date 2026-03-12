package database.currency

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update

@Dao
interface CurrencyDao {
    @Insert
    suspend fun insert(currencyEntity: CurrencyEntity)

    @Update
    suspend fun update(currencyEntity: CurrencyEntity)

    @Delete
    fun delete(currencyEntity: CurrencyEntity)
}
