import ExpenseList
import SwiftUI

/// The Expenses tab, run by the shared module.
struct ExpenseListScreen: View {
    @State private var callBacks = ExpenseCallBacksImpl()

    /// What a tapped notification asks the tab to open.
    @ObservedObject private var route = NotificationRoute.shared

    var body: some View {
        ExpenseListView(callBacks: callBacks, opening: $route.expense)
    }
}
