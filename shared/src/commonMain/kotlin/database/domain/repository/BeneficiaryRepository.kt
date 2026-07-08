package database.domain.repository

import database.domain.Beneficiary

interface BeneficiaryRepository {
    suspend fun insert(beneficiary: Beneficiary)

    suspend fun update(beneficiary: Beneficiary)

    suspend fun delete(beneficiary: Beneficiary)
}
