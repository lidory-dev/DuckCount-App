import AppColors
import AppFonts
import SwiftUI

/// The user's one-time expenses, newest first under the day each happened on, which open on their
/// own screen, and the way to add one: the Expenses tab, as Android's `ExpenseListScreen`.
public struct ExpenseListView: View {
    private let callBacks: any ExpenseCallBacks

    @StateObject private var viewModel: ExpenseListViewModel

    /// The expense the app asks to open, a notification about it having been tapped: opened once,
    /// then cleared, as Android opens the one an intent names.
    @Binding private var opening: String?

    @State private var selection: String?
    @State private var isCreating = false

    @Environment(\.scenePhase) private var scenePhase

    /// The app's, which a pinned day keeps, see `ExpenseDayHeader`.
    @Environment(\.colorScheme) private var colorScheme

    public init(callBacks: any ExpenseCallBacks, opening: Binding<String?> = .constant(nil)) {
        self.callBacks = callBacks
        _opening = opening
        _viewModel = StateObject(wrappedValue: ExpenseListViewModel(callBacks: callBacks))
    }

    public var body: some View {
        NavigationSplitView {
            // The list ends where the bar does, as Android's ends at its top bar: an expense scrolled
            // past the pinned day goes out of sight, where iOS would let it run under the bar,
            // blurred and showing. A list reaches under the bar only when it touches the top of
            // the safe area, which this point keeps it from. With its title inline, as Android's
            // top bar has it: a large title would no longer shrink into the bar as the list scrolls.
            VStack(spacing: 0) {
                Color.clear.frame(height: 1)

                list
                    .withoutTopScrollEdgeEffect()
            }
                .appBackground()
                .navigationTitle(Text("Expenses", bundle: .module))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            isCreating = true
                        } label: {
                            Label {
                                Text("New Expense", bundle: .module)
                            } icon: {
                                Image(systemName: "plus")
                            }
                        }
                    }
                }
        } detail: {
            NavigationStack {
                if let selection {
                    ExpenseDetailView(expenseId: selection, callBacks: callBacks) {
                        self.selection = nil
                    }
                    .id(selection)
                } else {
                    ContentUnavailableView {
                        Label {
                            Text("No Expense Selected", bundle: .module)
                        } icon: {
                            Image(systemName: "receipt")
                        }
                        .font(.app(.title2))
                    }
                    .appBackground()
                }
            }
        }
        .sheet(isPresented: $isCreating) {
            CreateExpenseView(callBacks: callBacks)
        }
        .onChange(of: opening, initial: true) { _, expenseId in
            if let expenseId {
                selection = expenseId
                opening = nil
            }
        }
        .task {
            await viewModel.refreshOnce()
        }
        // No push reaches iOS yet, so coming back to the app is when what changed meanwhile is read.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await viewModel.refresh()
                }
            }
        }
    }

    private var list: some View {
        List(selection: $selection) {
            ForEach(viewModel.days) { day in
                Section {
                    ForEach(day.expenses) { expense in
                        ExpenseRow(
                            expense: expense,
                            creditorName: viewModel.names.displayName(of: expense.creditorUuid),
                            selfUuid: viewModel.selfUuid
                        )
                        // Under the card, which shows no chevron, as Android's card has none.
                        .background {
                            NavigationLink(value: expense.id) {
                                EmptyView()
                            }
                            .opacity(0)
                        }
                        .expenseCard(isDeleted: expense.isDeleted)
                        .onAppear { viewModel.rowAppeared(expense.id) }
                    }
                } header: {
                    ExpenseDayHeader(title: day.date?.dayTitle ?? day.id, colorScheme: colorScheme)
                }
            }
        }
        .appListBackground()
        .listStyle(.plain)
        .refreshable {
            await viewModel.refresh()
        }
        .overlay {
            if viewModel.isLoading {
                ProgressView()
            } else if viewModel.days.isEmpty {
                ContentUnavailableView {
                    Label {
                        Text("You don’t have any expenses yet", bundle: .module)
                    } icon: {
                        Image(systemName: "receipt")
                    }
                    .font(.app(.title2))
                } actions: {
                    Button {
                        isCreating = true
                    } label: {
                        Text("New Expense", bundle: .module)
                    }
                }
            }
        }
    }
}

/// An expense in the list, as Android's `ExpenseCard` shows it: what it was for and who paid, what it
/// came to, and what the user is owed of it or owes, or that it was paid back. A settlement says who
/// paid whom back instead, and a deleted expense of a group is still listed, struck through and
/// dimmed. In `onSecondary`, its card being `secondary`, see `expenseCard(isDeleted:)`, at the sizes
/// Android sets it in: its title and amount in `titleLarge`, who paid in an italic `labelMedium`.
struct ExpenseRow: View {
    let expense: Expense
    let creditorName: String

    /// Who a settlement paid back, see `Expense.settlementRecipientUuid`: unused for any other
    /// expense.
    var recipientName = ""

    let selfUuid: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                title
                    .font(.app(.title2))
                    .lineLimit(1)

                Group {
                    if expense.isSettlement {
                        Text("\(creditorName) paid \(recipientName)", bundle: .module)
                    } else {
                        Text("Paid by \(creditorName)", bundle: .module)
                    }
                }
                .font(.app(.caption))
                .italic()
                .lineLimit(1)
            }

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 2) {
                Text(expense.amount.formattedAmount(in: expense.currency))
                    .font(.app(.title2))
                    .monospacedDigit()
                    .lineLimit(1)

                // Nobody owes anything of it any more.
                if expense.isSettled {
                    // The card's colour, where the tint does not read.
                    SettledLabel(color: AppColors.onSecondary)
                        .font(.app(.subheadline))
                        .labelStyle(InlineLabelStyle())
                        .lineLimit(1)
                } else if let balance = expense.shownBalance(selfUuid: selfUuid) {
                    Text(balance.formatted(in: expense.currency, withSign: true))
                        .font(.app(.subheadline))
                        .monospacedDigit()
                        .foregroundStyle(expense.role(selfUuid: selfUuid).color)
                        .lineLimit(1)
                }
            }
            .layoutPriority(1)
        }
        .foregroundStyle(AppColors.onSecondary)
        // Every text of a deleted one, which counts in no balance any more, its card dimmed with it.
        .strikethrough(expense.isDeleted)
        .opacity(expense.isDeleted ? ExpenseCard.deletedOpacity : 1)
        // Which VoiceOver says, as it says neither of those.
        .accessibilityElement(children: .combine)
        .accessibilityValue(expense.isDeleted ? Text("Deleted", bundle: .module) : Text(verbatim: ""))
    }

    @ViewBuilder
    private var title: some View {
        if expense.isSettlement {
            Label {
                Text("Settlement", bundle: .module)
            } icon: {
                Image(systemName: "arrow.left.arrow.right")
            }
            .labelStyle(InlineLabelStyle())
        } else {
            Text(expense.title)
        }
    }
}

/// The day a list's expenses happened on, above them and pinned to the top while they scroll, as
/// Android's `StickyHeaderElevated`: on a raised card of the background's colour, the background
/// itself fading out below it, so that the cards going under it never show through the date.
///
/// In the app's `colorScheme`, which the list reads outside the header: iOS 26 turns what is pinned
/// at the top of a list dark when dark content scrolls under it, as the green cards do, which turned
/// the background's colour near black, and the card with it.
struct ExpenseDayHeader: View {
    let title: String
    let colorScheme: ColorScheme

    var body: some View {
        Text(title)
            .font(.app(.body))
            .foregroundStyle(AppColors.onSurface)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(8)
            .background(AppColors.background, in: RoundedRectangle(cornerRadius: ExpenseCard.cornerRadius, style: .continuous))
            // Android's card is raised by 4 dp, as an expense's is.
            .shadow(color: .black.opacity(0.2), radius: 2, y: 1)
            .padding(4)
            .padding(.bottom, 8)
            .background(
                LinearGradient(
                    stops: [
                        .init(color: AppColors.background, location: 0.5),
                        .init(color: AppColors.background.opacity(0), location: 1),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .environment(\.colorScheme, colorScheme)
            // Across the whole list, as Android's, rather than within a header's insets.
            .listRowInsets(EdgeInsets())
    }
}

private extension View {
    /// No edge effect at the top of a list that stops where the bar does, which leaves it nothing to
    /// blur, and whose iOS 26 one would still turn the bar's title and the status bar white, as over
    /// dark content, when a green card comes up under it, unreadable on the light background.
    @ViewBuilder
    func withoutTopScrollEdgeEffect() -> some View {
        if #available(iOS 26.0, *) {
            scrollEdgeEffectHidden(true, for: .top)
        } else {
            self
        }
    }
}

/// A label's icon right by its title, 4 pt apart as Android's card sets them, where a list would set
/// it in a column of its own, far from its title.
private struct InlineLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.icon
            configuration.title
        }
    }
}

/// The card an expense's row is drawn on, as Android's `ExpenseCard`, an elevated Material card on
/// `secondary`, 8 pt from the screen's edges and from the next card, its content 16 pt in (12 pt on
/// the amount's side, which Android pads no further).
enum ExpenseCard {
    /// A deleted expense's, whole card: Android's.
    static let deletedOpacity = 0.75

    static let cornerRadius: CGFloat = 12
    fileprivate static let margins = EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8)
    fileprivate static let contentInsets = EdgeInsets(top: 20, leading: 24, bottom: 20, trailing: 20)
}

extension View {
    /// This row of an expense list on its card, see `ExpenseCard`: no separator between cards.
    func expenseCard(isDeleted: Bool) -> some View {
        listRowInsets(ExpenseCard.contentInsets)
            .listRowSeparator(.hidden)
            .listRowBackground(
                RoundedRectangle(cornerRadius: ExpenseCard.cornerRadius, style: .continuous)
                    .fill(AppColors.secondary)
                    // Android's card is raised by 4 dp.
                    .shadow(color: .black.opacity(0.2), radius: 2, y: 1)
                    .padding(ExpenseCard.margins)
                    .opacity(isDeleted ? ExpenseCard.deletedOpacity : 1)
            )
    }
}

#Preview {
    ExpenseListView(callBacks: PreviewExpenseCallBacks())
}
