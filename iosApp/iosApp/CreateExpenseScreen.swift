import ExpenseList
import SwiftUI

struct CreateExpenseScreen: View {
    @StateObject private var viewModel = CreateExpenseViewModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        CreateExpenseView(
            viewModel: viewModel,
            onCancel: { dismiss() },
            onSave: {
                viewModel.createExpense()
                dismiss()
            }
        )
    }
}
