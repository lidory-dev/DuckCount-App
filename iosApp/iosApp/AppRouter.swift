import SwiftUI

enum Route: Hashable {
    case expenseList
    case expenseDetail(id: Int)
}

class AppRouter: ObservableObject {
    @Published var path = NavigationPath()

    func route(to: Route) {
        path.append(to)
    }

    func reset() {
        path = NavigationPath()
    }
}
