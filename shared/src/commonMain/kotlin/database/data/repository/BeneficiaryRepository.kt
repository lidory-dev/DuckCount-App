package database.data.repository

import database.data.local.dao.BeneficiaryDao
import database.data.mappers.toBeneficiaryEntity
import database.domain.Beneficiary

class BeneficiaryRepository(
    private val beneficiaryDao: BeneficiaryDao,
) {
    suspend fun insert(beneficiary: Beneficiary) {
        beneficiaryDao.insert(beneficiaryEntity = beneficiary.toBeneficiaryEntity())
    }

    suspend fun update(beneficiary: Beneficiary) {
        beneficiaryDao.update(beneficiaryEntity = beneficiary.toBeneficiaryEntity())
    }

    suspend fun delete(beneficiary: Beneficiary) {
        beneficiaryDao.delete(beneficiaryEntity = beneficiary.toBeneficiaryEntity())
    }
}
