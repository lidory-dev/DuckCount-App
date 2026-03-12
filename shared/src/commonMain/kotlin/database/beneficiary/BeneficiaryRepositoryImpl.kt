package database.beneficiary

class BeneficiaryRepositoryImpl(
    private val beneficiaryDao: BeneficiaryDao,
) : BeneficiaryRepository {
    override suspend fun insert(beneficiary: Beneficiary) {
        beneficiaryDao.insert(beneficiaryEntity = beneficiary.toBeneficiaryEntity())
    }

    override suspend fun update(beneficiary: Beneficiary) {
        beneficiaryDao.update(beneficiaryEntity = beneficiary.toBeneficiaryEntity())
    }

    override suspend fun delete(beneficiary: Beneficiary) {
        beneficiaryDao.delete(beneficiaryEntity = beneficiary.toBeneficiaryEntity())
    }
}
