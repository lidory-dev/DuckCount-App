import AppFonts
import SwiftUI

/// The versions of an expense, the one shown first, each with when it was made and by whom, as
/// Android's `ExpenseHistoryScreen`.
struct ExpenseHistoryView: View {
    @StateObject private var viewModel: ExpenseHistoryViewModel

    init(expenseId: String, callBacks: any ExpenseCallBacks) {
        _viewModel = StateObject(wrappedValue: ExpenseHistoryViewModel(expenseId: expenseId, callBacks: callBacks))
    }

    var body: some View {
        Group {
            switch viewModel.state {
            case .loading:
                ProgressView()

            case .failed(let error):
                ContentUnavailableView {
                    Label {
                        Text(error.message)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle")
                    }
                    .font(.app(.title2))
                }

            case .loaded(let versions):
                List(versions) { version in
                    NavigationLink {
                        ExpenseVersionView(version: version, viewModel: viewModel)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            if let date = version.madeAt.flatMap(LocalDateTime.date(from:)) {
                                Text(date.dayAndTimeTitle)
                                    .font(.app(.headline))
                            }

                            AuthorLabel(version: version, name: viewModel.names.displayName(of: version.authorUuid))
                                .font(.app(.subheadline))
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
        }
        .navigationTitle(Text("History", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await viewModel.load()
        }
    }
}

/// One version of an expense as it was, as Android's `ExpenseHistoryDetailScreen`.
struct ExpenseVersionView: View {
    let version: ExpenseVersion

    @ObservedObject var viewModel: ExpenseHistoryViewModel

    var body: some View {
        let expense = version.expense

        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    AuthorLabel(version: version, name: viewModel.names.displayName(of: version.authorUuid))
                        .font(.app(.subheadline))
                        .foregroundStyle(.secondary)

                    Text(expense.title)
                        .font(.app(.title2))

                    if !expense.description.isBlank {
                        Text(expense.description)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            }

            Section {
                PersonAmountRow(
                    userUuid: expense.creditorUuid,
                    name: viewModel.names.displayName(of: expense.creditorUuid),
                    amount: expense.amount,
                    currency: expense.currency
                )
            } header: {
                Text("Paid by", bundle: .module)
            }

            Section {
                ForEach(viewModel.beneficiaries(of: version), id: \.userUuid) { beneficiary in
                    PersonAmountRow(
                        userUuid: beneficiary.userUuid,
                        name: viewModel.names.displayName(of: beneficiary.userUuid),
                        amount: beneficiary.share,
                        currency: expense.currency
                    )
                }
            } header: {
                Text("Beneficiaries", bundle: .module)
            }
        }
        .navigationTitle(version.madeAt.flatMap(LocalDateTime.date(from:))?.shortDayAndTimeTitle ?? "")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Who made a version: "Created by" its author, or "Modified by" them, the creditor of a one-time
/// expense, or whichever member of its group made a group expense's.
private struct AuthorLabel: View {
    let version: ExpenseVersion
    let name: String

    var body: some View {
        if version.isCreation {
            Text("Created by \(name)", bundle: .module)
        } else {
            Text("Modified by \(name)", bundle: .module)
        }
    }
}
