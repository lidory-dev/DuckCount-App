import ExpenseList
import SwiftUI

/// The Expenses tab, run by the shared module.
struct ExpenseListScreen: View {
    @State private var callBacks = ExpenseCallBacksImpl()

    var body: some View {
        ExpenseListView(callBacks: callBacks)
    }
}
