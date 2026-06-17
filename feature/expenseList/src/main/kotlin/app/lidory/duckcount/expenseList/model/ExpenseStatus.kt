package app.lidory.duckcount.expenseList.model

import androidx.compose.ui.graphics.Color

enum class ExpenseStatus(val labelAmount: String, val color: Color) {
    BENEFICIARY(
        labelAmount = "+",
        color = Color(0xff5ccb5f),
    ),
    CREDITOR(
        labelAmount = "-",
        color = Color(0xffe63946),
    ),
    NEUTRAL(
        labelAmount = "",
        color = Color.LightGray,
    )
}