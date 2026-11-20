import AppColors
import AppFonts
import SwiftUI

/// The tabs of a group's screen, which a segmented control switches between where Android swipes
/// a pager.
enum GroupTab: Hashable, CaseIterable, Identifiable {
    case expenses
    case balance
    case settleUp

    var id: Self {
        self
    }

    var title: Text {
        switch self {
        case .expenses: Text("Expenses", bundle: .module)
        case .balance: Text("Balance", bundle: .module)
        case .settleUp: Text("Settle up", bundle: .module)
        }
    }
}

/// A group: its expenses, who owes what, and what would settle it, with why it is read-only when
/// the user is no longer in it, as Android's `GroupDetailScreen`. Its title leads to its settings.
struct GroupDetailView: View {
    private let callBacks: any GroupCallBacks
    private let destinations: GroupDestinations
    private let onClose: () -> Void

    /// The expense of the group the app asks to open, which its Expenses tab opens, see
    /// `GroupOpening`.
    @Binding private var openingExpense: String?

    @StateObject private var viewModel: GroupDetailViewModel
    @StateObject private var balances: GroupBalancesViewModel

    @State private var tab = GroupTab.expenses
    @State private var isShowingSettings = false

    init(
        groupId: String,
        callBacks: any GroupCallBacks,
        destinations: GroupDestinations,
        openingExpense: Binding<String?> = .constant(nil),
        onClose: @escaping () -> Void
    ) {
        self.callBacks = callBacks
        self.destinations = destinations
        self.onClose = onClose
        _openingExpense = openingExpense
        _viewModel = StateObject(wrappedValue: GroupDetailViewModel(groupId: groupId, callBacks: callBacks))
        _balances = StateObject(wrappedValue: GroupBalancesViewModel(groupId: groupId, callBacks: callBacks))
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            content
                .frame(maxHeight: .infinity)
        }
        .navigationTitle(viewModel.group?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar }
        .navigationDestination(isPresented: $isShowingSettings) {
            GroupSettingsView(groupId: viewModel.groupId, callBacks: callBacks, destinations: destinations)
        }
        // For whichever tab shows, the expenses' own list included.
        .refreshable {
            await viewModel.refresh()
        }
        // Again each time the group shows, the device holding what it has of it meanwhile.
        .task {
            await viewModel.refresh()
        }
        // An expense to open is on the Expenses tab, whichever one shows.
        .onChange(of: openingExpense, initial: true) { _, expenseId in
            if expenseId != nil {
                tab = .expenses
            }
        }
        .onChange(of: viewModel.isGone, initial: true) { _, isGone in
            if isGone {
                onClose()
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        // Its title and description, which lead to its settings as Android's top bar does.
        ToolbarItem(placement: .principal) {
            Button {
                isShowingSettings = true
            } label: {
                VStack(spacing: 0) {
                    Text(viewModel.group?.title ?? "")
                        .font(.app(.headline))
                        .foregroundStyle(Color.primary)
                        .lineLimit(1)

                    if let description = viewModel.group?.description, !description.isBlank {
                        Text(description)
                            .font(.app(.caption))
                            .foregroundStyle(AppColors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
            }
            .accessibilityHint(Text("Group settings", bundle: .module))
        }

        ToolbarItem(placement: .topBarTrailing) {
            Button {
                isShowingSettings = true
            } label: {
                Label {
                    Text("Group settings", bundle: .module)
                } icon: {
                    Image(systemName: "gearshape")
                }
            }
        }
    }

    private var header: some View {
        VStack(spacing: 8) {
            if let group = viewModel.group, group.isLeft {
                NoticeCard(GroupNotices.notice(isDeleted: group.isDeleted))
            }

            if viewModel.unreadableExpenseCount > 0 {
                NoticeCard(
                    String(
                        localized: "\(viewModel.unreadableExpenseCount) expenses can’t be read on this device yet, so the balances leave them out",
                        bundle: .module
                    )
                )
            }

            Picker(selection: $tab) {
                ForEach(GroupTab.allCases) { tab in
                    tab.title.tag(tab)
                }
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .animation(.default, value: viewModel.group?.isLeft)
        .animation(.default, value: viewModel.unreadableExpenseCount)
    }

    @ViewBuilder
    private var content: some View {
        switch tab {
        case .expenses:
            destinations.expenses(viewModel.groupId, $openingExpense)

        case .balance:
            BalanceTabView(viewModel: balances)

        case .settleUp:
            SettleUpTabView(viewModel: balances, canSettle: viewModel.canSettle)
        }
    }
}
