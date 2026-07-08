package database.data.repository

import database.domain.repository.BeneficiaryRepository
import database.data.local.dao.BeneficiaryDao
import database.data.mappers.toBeneficiaryEntity
import database.domain.Beneficiary

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
