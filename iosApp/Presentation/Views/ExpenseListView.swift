import SwiftUI

struct ExpenseListView: View {
    @EnvironmentObject var router: AppRouter

    var body: some View {
        Button("Go to ExpenseDetail") {
            router.route(to: Route.expenseDetail(id: 3))
        }
    }
}

#Preview {
    ExpenseListView()
}
