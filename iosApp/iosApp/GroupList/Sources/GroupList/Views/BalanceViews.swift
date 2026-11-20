import AppColors
import AppFonts
import SwiftUI

/// What everybody comes to in a group, each on a bar of their own, green to the right for who is
/// owed, red to the left for who owes, as Android's `GroupBalanceScreen`. Read-only, and shown the
/// same in a group the user is no longer in.
struct BalanceTabView: View {
    @ObservedObject var viewModel: GroupBalancesViewModel

    var body: some View {
        let rows = viewModel.balanceRows

        List(rows) { row in
            BalanceBarRow(name: viewModel.name(of: row), label: viewModel.amountLabel(row.amount), row: row)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
                .appPlainRows()
        }
        .appListBackground()
        .listStyle(.plain)
        .overlay {
            switch viewModel.state {
            case .loading:
                ProgressView()

            case .unavailable:
                BalancesUnavailableView()

            case .content:
                if rows.isEmpty {
                    ContentUnavailableView {
                        Label {
                            Text("Everything is balanced", bundle: .module)
                        } icon: {
                            Image(systemName: "scalemass")
                        }
                        .font(.app(.title2))
                    }
                }
            }
        }
    }
}

/// One person's balance, as Android's `BalanceGraphItem` draws it: their name on one half, and on
/// the other a bar from the middle, as long against the longest as what they come to.
struct BalanceBarRow: View {
    let name: String
    let label: String
    let row: BalanceRow

    @Environment(\.colorScheme) private var colorScheme

    private var isOwing: Bool {
        row.value < 0
    }

    var body: some View {
        HStack(spacing: 0) {
            if isOwing {
                bar
                nameHalf
            } else {
                nameHalf
                bar
            }
        }
        .frame(height: 40)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: name))
        .accessibilityValue(Text(verbatim: label))
    }

    private var nameHalf: some View {
        Text(name)
            .lineLimit(1)
            .padding(.horizontal, 8)
            // Next to the middle, where its bar starts.
            .frame(maxWidth: .infinity, alignment: isOwing ? .leading : .trailing)
    }

    private var bar: some View {
        GeometryReader { geometry in
            ZStack(alignment: isOwing ? .trailing : .leading) {
                UnevenRoundedRectangle(
                    topLeadingRadius: isOwing ? 8 : 0,
                    bottomLeadingRadius: isOwing ? 8 : 0,
                    bottomTrailingRadius: isOwing ? 0 : 8,
                    topTrailingRadius: isOwing ? 0 : 8,
                    style: .continuous
                )
                .fill(color)
                .frame(width: geometry.size.width * row.fraction)

                Text(label)
                    .monospacedDigit()
                    .lineLimit(1)
                    .padding(.horizontal, 8)
            }
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: isOwing ? .trailing : .leading)
        }
        .frame(maxWidth: .infinity)
    }

    /// Android's colors, a light one under dark text and a dark one under light text.
    private var color: Color {
        switch (isOwing, colorScheme == .dark) {
        case (true, false): Color(rgb: 0xFBA5A2)
        case (true, true): Color(rgb: 0x890401)
        case (false, false): Color(rgb: 0xAECCA2)
        case (false, true): Color(rgb: 0x0A4401)
        }
    }
}

/// Every repayment that would settle a group, each worded for the user, with the way to record
/// one paid back to them, as Android's `GroupSettleUpScreen`.
struct SettleUpTabView: View {
    @ObservedObject var viewModel: GroupBalancesViewModel

    /// Whether a settlement can be recorded: not in a group the user is no longer in.
    let canSettle: Bool

    var body: some View {
        let debts = viewModel.debts

        List(debts) { debt in
            row(debt)
                .appPlainRows()
        }
        .appListBackground()
        .listStyle(.plain)
        .overlay {
            switch viewModel.state {
            case .loading:
                ProgressView()

            case .unavailable:
                BalancesUnavailableView()

            case .content:
                if debts.isEmpty {
                    ContentUnavailableView {
                        Label {
                            Text("You’re all settled up", bundle: .module)
                        } icon: {
                            Image(systemName: "checkmark.seal")
                        }
                        .font(.app(.title2))
                    }
                }
            }
        }
        .alert(
            viewModel.settlementError?.message ?? "",
            isPresented: Binding(
                get: { viewModel.settlementError != nil },
                set: { if !$0 { viewModel.settlementError = nil } }
            )
        ) {
            Button(role: .cancel) {} label: {
                Text("OK", bundle: .module)
            }
        }
    }

    private func row(_ debt: Debt) -> some View {
        let side = debt.side(for: viewModel.selfUuid)

        return HStack(spacing: 12) {
            Text(viewModel.sentence(for: debt))
                .foregroundStyle(side == .betweenOthers ? AppColors.onSurfaceVariant : Color.primary)
                .frame(maxWidth: .infinity, alignment: .leading)

            // Only the creditor records a debt paid back, at once, as on Android.
            if side == .owedToUser, canSettle {
                if viewModel.settlingDebtId == debt.id {
                    ProgressView()
                } else {
                    Button {
                        viewModel.settle(debt)
                    } label: {
                        Label {
                            Text("Validate", bundle: .module)
                        } icon: {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.title2)
                        }
                        .labelStyle(.iconOnly)
                    }
                    .buttonStyle(.borderless)
                    .disabled(viewModel.settlingDebtId != nil)
                }
            }
        }
        .padding(.vertical, 6)
    }
}

/// What the Balance and Settle up tabs show when the balances cannot be worked out.
private struct BalancesUnavailableView: View {
    var body: some View {
        ContentUnavailableView {
            Label {
                Text("The balances can’t be worked out right now", bundle: .module)
            } icon: {
                Image(systemName: "exclamationmark.triangle")
            }
            .font(.app(.title2))
        }
    }
}
