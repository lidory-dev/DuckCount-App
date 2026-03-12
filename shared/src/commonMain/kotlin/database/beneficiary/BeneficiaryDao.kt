package database.beneficiary

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update

@Dao
interface BeneficiaryDao {
    @Insert
    suspend fun insert(beneficiaryEntity: BeneficiaryEntity)

    @Update
    suspend fun update(beneficiaryEntity: BeneficiaryEntity)

    @Delete
    suspend fun delete(beneficiaryEntity: BeneficiaryEntity)
}
