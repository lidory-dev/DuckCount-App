package database.beneficiary

interface BeneficiaryRepository {
    suspend fun insert(beneficiary: Beneficiary)

    suspend fun update(beneficiary: Beneficiary)

    suspend fun delete(beneficiary: Beneficiary)
}
