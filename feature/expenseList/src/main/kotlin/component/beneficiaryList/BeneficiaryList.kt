package component.beneficiaryList

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
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

        if (expenseError != null) {
            ExpenseErrorCard(
                error = expenseError,
                modifier = Modifier
                    .fillMaxWidth(),
            )
        }
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