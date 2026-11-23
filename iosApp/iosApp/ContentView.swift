import AppColors
import Authentication
import Shared
import SwiftUI
import ExpenseList

struct ContentView: View {
    enum Tabs: String, CaseIterable {
        case expenseList
        case groupList
        case contactList
        case settings
    }

    @AppStorage("selectedTab") var selectedTab: Tabs = .expenseList

    @StateObject private var updateRequirement = UpdateRequirementViewModel()

    @StateObject private var startupViewModel = StartupViewModel()

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .overlay {
                // Over whatever the app starts on, which it then fades away from.
                ZStack {
                    if startupViewModel.isLaunchScreenShown {
                        LaunchScreenView()
                            .ignoresSafeArea()
                            .transition(.opacity)
                    }
                }
                .animation(.default, value: startupViewModel.isLaunchScreenShown)
            }
    }

    @ViewBuilder
    private var content: some View {
        if updateRequirement.isRequired {
            UpdateRequiredScreen()
                .background(AppColors.background)
        } else {
            Group {
                switch startupViewModel.startup {
                // Brief, while the device's own storage is read, so that the onboarding screen
                // never flashes by on the way to the app.
                case .loading:
                    AppColors.background
                        .ignoresSafeArea()

                case .loggedIn:
                    tabs
                        .task { await startupViewModel.publishKeyPackagesIfDue() }
                        .task { await PushSetUp.run() }
                        // What a notification said about the tab the user looks at, they are reading.
                        .onChange(of: selectedTab, initial: true) { Notifier.shared.tabShown(selectedTab) }
                        .onChange(of: scenePhase) {
                            if scenePhase == .active {
                                Notifier.shared.tabShown(selectedTab)
                            }
                        }

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
                // A new session starts on the first tab, whichever one the last session left open.
                selectedTab = .expenseList

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
                    .tabBackground()
            }

            Tab("Groups", systemImage: "person.2.crop.square.stack.fill", value: .groupList) {
                GroupListScreen()
                    .tabBackground()
            }

            Tab("Contacts", systemImage: "person.3.fill", value: .contactList) {
                ContactListScreen()
                    .tabBackground()
            }

            Tab("Settings", systemImage: "gearshape.fill", value: .settings) {
                SettingsScreen()
                    .tabBackground()
            }
        }
        .transition(.opacity)
    }
}

private extension View {
    /// A tab on Android's `background` up to the top of the screen. On an iPad the tab bar sits
    /// there, in a band the tab's split view stops below, which the tab would otherwise leave in
    /// iOS's white or black, its columns' backgrounds reaching no higher than they do.
    func tabBackground() -> some View {
        background(AppColors.background, ignoresSafeAreaEdges: .all)
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
