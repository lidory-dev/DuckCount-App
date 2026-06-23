package app.lidory.duckcount.expenseList.component.beneficiaryList

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.res.stringResource
import app.lidory.duckcount.expenseList.component.ExpenseErrorCard
import app.lidory.duckcount.expenseList.model.BeneficiaryUi

fun LazyListScope.beneficiaryList(
    beneficiaries: List<BeneficiaryUi>,
    currencySymbol: String,
    onBeneficiaryAmountChange: (String, String) -> Unit,
    onModifyBeneficiaryList: () -> Unit,
    @StringRes expenseError: Int?,
    focusManager: FocusManager,
    removeBeneficiaryFixedShare: (String) -> Unit,
) {
    item {
        BeneficiaryListHeader(
            isBeneficiaryListEmpty = beneficiaries.isEmpty(),
            onModifyBeneficiaryList = onModifyBeneficiaryList,
        )

        expenseError?.let {
            ExpenseErrorCard(
                modifier = Modifier
                    .fillMaxWidth(),
                text = stringResource(it),
            )
        }
    }

    items(
        items = beneficiaries,
        key = { beneficiary -> beneficiary.uuid },
    ) { beneficiary ->
        BeneficiaryRow(
            beneficiary = beneficiary,
            currencySymbol = currencySymbol,
            onBeneficiaryAmountChange = onBeneficiaryAmountChange,
            focusManager = focusManager,
            removeFixedShare = {
                removeBeneficiaryFixedShare(beneficiary.userUuid)
            },
        )
    }
}