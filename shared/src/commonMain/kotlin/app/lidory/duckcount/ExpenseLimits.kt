package app.lidory.duckcount

import uniffi.lib_duckcount.maxBeneficiaries as maxBeneficiariesUniffi

object ExpenseLimits {
    const val MAX_TITLE_LENGTH = 50

    const val MAX_DESCRIPTION_LENGTH = 2000

    /**
     * The most beneficiaries an expense may list, its creditor included when listed, for a
     * one-time expense and a group's alike. Read from the library, so the form counts against
     * the very limit [validateExpense] enforces rather than repeating the number: creating a
     * one-time expense sends a key message to each of them, and the server refuses a longer list.
     */
    val MAX_BENEFICIARIES: Int = maxBeneficiariesUniffi().toInt()
}
