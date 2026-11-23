package app.lidory.duckcount.expenseList.model

import androidx.compose.ui.graphics.Color

enum class ExpenseStatus(val color: Color) {
    BENEFICIARY(
        color = Color(0xffe63946),
    ),
    CREDITOR(
        color = Color(0xff5ccb5f),
    ),
    NEUTRAL(
        color = Color.LightGray,
    )
}