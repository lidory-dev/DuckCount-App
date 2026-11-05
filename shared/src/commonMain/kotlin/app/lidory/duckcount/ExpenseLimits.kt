package app.lidory.duckcount

import uniffi.lib_duckcount.maxBeneficiaries as maxBeneficiariesUniffi
import uniffi.lib_duckcount.maxGroupExpenseEdits as maxGroupExpenseEditsUniffi
import uniffi.lib_duckcount.sealedAmountLength as sealedAmountLengthUniffi

object ExpenseLimits {
    const val MAX_TITLE_LENGTH = 50

    const val MAX_DESCRIPTION_LENGTH = 500

    /**
     * The most beneficiaries an expense may list, its creditor included when listed, for a
     * one-time expense and a group's alike. Read from the library, so the form counts against
     * the very limit [validateExpense] enforces rather than repeating the number: creating a
     * one-time expense sends a key message to each of them, and the server refuses a longer list.
     */
    val MAX_BENEFICIARIES: Int = maxBeneficiariesUniffi().toInt()

    /**
     * The most edits an expense of a group takes, its revision going from 1, as created, to one
     * more than this at most. Read from the library, which refuses a version past that last
     * revision whoever signed it, as the server refuses to store one, rather than repeating the
     * number. Deleting and restoring the expense are no edit, see `group.GroupExpenseWriter`: one
     * that had every edit it takes is still deleted and restored.
     */
    val MAX_GROUP_EXPENSE_EDITS: Int = maxGroupExpenseEditsUniffi().toInt()

    /**
     * How long every amount and share is sealed, padded up to it, see `expense.toSealedAmount`:
     * as long as the longest amount there is. Read from the library, which decides how long that
     * is, rather than repeating the number.
     */
    val SEALED_AMOUNT_LENGTH: Int = sealedAmountLengthUniffi().toInt()
}
