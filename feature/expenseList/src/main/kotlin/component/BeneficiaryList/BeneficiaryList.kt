package component.BeneficiaryList

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.FocusManager
import app.lidory.duckcount.ExpenseError
import component.ExpenseErrorCard
import database.beneficiary.Beneficiary

fun LazyListScope.beneficiaryList(
    beneficiaries: List<Beneficiary>,
    onBeneficiaryAmountChange: (String, String) -> Unit,
    onAddUser: () -> Unit,
    expenseError: ExpenseError?,
    focusManager: FocusManager,
) {
    item {
        BeneficiaryListHeader(onAddUser = onAddUser)
    }
    
    item {
        ExpenseErrorCard(expenseError)
    }

    items(
        items = beneficiaries,
        key = { beneficiary -> beneficiary.uuid },
    ) { beneficiary ->
        BeneficiaryRow(
            beneficiary = beneficiary,
            onBeneficiaryAmountChange = onBeneficiaryAmountChange,
            focusManager = focusManager,
        )
    }
}