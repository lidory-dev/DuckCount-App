import Shared
import SwiftUI
import ExpenseList

struct ContentView: View {
    enum Tabs: String, CaseIterable {
        case expenseList
        case groupList
        case contactList
    }

    @AppStorage("selectedTab") var selectedTab: Tabs = .expenseList

    var body: some View {
        TabView(selection: $selectedTab) {
            Tab("Expenses", systemImage: "receipt.fill", value: .expenseList) {
                ExpenseListScreen()
            }

            Tab("Groups", systemImage: "person.2.crop.square.stack.fill", value: .groupList) {
                // TODO:
                Color.red.ignoresSafeArea()
            }

            Tab("Contacts", systemImage: "person.3.fill", value: .contactList) {
                ContactListScreen()
            }
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
