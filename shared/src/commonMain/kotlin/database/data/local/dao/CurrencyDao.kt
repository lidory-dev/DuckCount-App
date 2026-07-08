package database.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update
import database.data.local.entity.CurrencyEntity

@Dao
interface CurrencyDao {
    @Insert
    suspend fun insert(currencyEntity: CurrencyEntity)

    @Update
    suspend fun update(currencyEntity: CurrencyEntity)

    @Delete
    suspend fun delete(currencyEntity: CurrencyEntity)
}