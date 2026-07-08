package database.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update
import database.data.local.entity.BeneficiaryEntity

@Dao
interface BeneficiaryDao {
    @Insert
    suspend fun insert(beneficiaryEntity: BeneficiaryEntity)

    @Update
    suspend fun update(beneficiaryEntity: BeneficiaryEntity)

    @Delete
    suspend fun delete(beneficiaryEntity: BeneficiaryEntity)
}