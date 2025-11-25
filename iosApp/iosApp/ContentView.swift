import Shared
import SwiftUI
import ExpenseList

struct ContentView: View {
    @StateObject var router = AppRouter()

    var body: some View {
        NavigationStack(path: $router.path) {
            ExpenseListView()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .expenseList:
                        ExpenseListView()
                    }
                }
        }
        .environmentObject(router)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
