import Authentication
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

    @StateObject private var updateRequirement = UpdateRequirementViewModel()

    @StateObject private var startupViewModel = StartupViewModel()

    var body: some View {
        if updateRequirement.isRequired {
            UpdateRequiredScreen()
        } else {
            Group {
                switch startupViewModel.startup {
                // Brief, while the device's own storage is read, so that the onboarding screen
                // never flashes by on the way to the app.
                case .loading:
                    Color(.systemBackground)
                        .ignoresSafeArea()

                case .loggedIn:
                    tabs
                        .task { await startupViewModel.publishKeyPackagesIfDue() }

                case .notLoggedIn:
                    authenticationFlow(resume: nil)

                case .resumeRegistration(let profile):
                    authenticationFlow(resume: profile)
                }
            }
        }
    }

    private func authenticationFlow(resume: PendingProfile?) -> some View {
        AuthenticationFlowView(
            callBacks: startupViewModel.authenticationCallBacks,
            resume: resume,
            onAuthenticated: {
                withAnimation {
                    startupViewModel.authenticated()
                }
            }
        )
        .transition(.opacity)
    }

    private var tabs: some View {
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
        .transition(.opacity)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
