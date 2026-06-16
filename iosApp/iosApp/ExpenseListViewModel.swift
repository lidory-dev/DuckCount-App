import ExpenseList
import Foundation
import Shared

typealias SwiftExpense = ExpenseList.Expense

struct ExpenseGroup: Identifiable {
    let id: String
    let displayDate: String
    let expenses: [SwiftExpense]
}

@MainActor
class ExpenseListViewModel: ObservableObject {
    @Published var groupedExpenses: [ExpenseGroup] = []
    @Published var isLoading: Bool = true

    // TODO: Replace with the actual logged-in user UUID once authentication is implemented.
    static let currentUserUuid = "00000000-0000-0000-0000-000000000001"

    private var cancellable: Cancellable?

    private let expenseRepository: Shared.ExpenseRepository
    private let beneficiaryRepository: Shared.BeneficiaryRepository
    private let userRepository: Shared.UserRepository

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()

        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")

        return formatter
    }()

    private static let displayFormatterCurrentYear: DateFormatter = {
        let formatter = DateFormatter()

        formatter.dateFormat = "EEEE dd MMMM"

        return formatter
    }()

    private static let displayFormatterWithYear: DateFormatter = {
        let formatter = DateFormatter()

        formatter.dateFormat = "EEEE dd MMMM yyyy"

        return formatter
    }()

    private static let groupKeyFormatter: DateFormatter = {
        let formatter = DateFormatter()

        formatter.dateFormat = "yyyy-MM-dd"

        return formatter
    }()

    init() {
        let helper = KoinHelper()

        self.expenseRepository = helper.getExpenseRepository()
        self.beneficiaryRepository = helper.getBeneficiaryRepository()
        self.userRepository = helper.getUserRepository()

        observeExpenses()
    }

    private func observeExpenses() {
        let flow = expenseRepository.getAll()

        cancellable = FlowCollectorKt.collect(
            flow,
            onEach: { [weak self] value in
                guard let self else {
                    return
                }
                guard let kotlinExpenses = value as? [Shared.ExpenseWithBeneficiaries] else {
                    return
                }

                let swiftExpenses = kotlinExpenses.map { kotlinExpense -> SwiftExpense in
                    let beneficiaryUuids = Set(
                        kotlinExpense.beneficiaries.map {
                            $0.userUuid
                        })
                    let status: ExpenseStatus
                    if kotlinExpense.creditorUuid == Self.currentUserUuid {
                        status = .creditor
                    } else if beneficiaryUuids.contains(Self.currentUserUuid) {
                        status = .beneficiary
                    } else {
                        status = .neutral
                    }

                    return SwiftExpense(
                        id: kotlinExpense.uuid,
                        timestamp: kotlinExpense.timestamp,
                        title: kotlinExpense.title,
                        description: kotlinExpense.description_,
                        amount: kotlinExpense.amount,
                        creditorUuid: kotlinExpense.creditorUuid,
                        isRefund: kotlinExpense.isRefund,
                        currency: Currency(
                            id: kotlinExpense.currency.uuid,
                            name: kotlinExpense.currency.name,
                            symbol: kotlinExpense.currency.symbol,
                            hasSubunits: kotlinExpense.currency.hasSubunits
                        ),
                        status: status
                    )
                }

                self.groupedExpenses = Self.groupByDate(expenses: swiftExpenses)
                self.isLoading = false
            }, onCompletion: { _ in })
    }

    private static func groupByDate(expenses: [SwiftExpense]) -> [ExpenseGroup] {
        let grouped = Dictionary(grouping: expenses) { expense -> String in
            if let date = timestampFormatter.date(from: expense.timestamp) {
                return groupKeyFormatter.string(from: date)
            }
            return "Unknown"
        }

        let currentYear = Calendar.current.component(.year, from: Date())

        return
            grouped
            .sorted {
                $0.key > $1.key
            }
            .map { key, expenses in
                let displayDate: String
                if let date = groupKeyFormatter.date(from: key) {
                    let year = Calendar.current.component(.year, from: date)
                    let formatter = year == currentYear ? displayFormatterCurrentYear : displayFormatterWithYear
                    displayDate = formatter.string(from: date)
                } else {
                    displayDate = key
                }

                let sortedExpenses = expenses.sorted {
                    $0.timestamp > $1.timestamp
                }

                return ExpenseGroup(id: key, displayDate: displayDate, expenses: sortedExpenses)
            }
    }

    func createRandomExpenses() {
        // Other user UUIDs used to populate beneficiaries and creditors in demo data.
        let otherUserUuids = [
            "00000000-0000-0000-0000-000000000002",
            "00000000-0000-0000-0000-000000000003",
            "00000000-0000-0000-0000-000000000004",
        ]

        let demoUsers: [(uuid: String, username: String, fullName: String)] = [
            (Self.currentUserUuid, "test", "Test User"),
            (otherUserUuids[0], "alice", "Alice Martin"),
            (otherUserUuids[1], "bob", "Bob Johnson"),
            (otherUserUuids[2], "charlie", "Charlie Davis"),
        ]

        for user in demoUsers {
            userRepository.insert(
                user: Shared.User(uuid: user.uuid, username: user.username, fullName: user.fullName)
            ) { _ in
            }
        }

        let titles = ["Groceries", "Coffee", "Lunch", "Dinner", "Gas", "Movie tickets", "Books", "Taxi", "Hotel", "Flight"]
        let descriptions = ["Weekly shopping", "Morning coffee", "Team lunch", "Birthday dinner", "Road trip", "Date night", "Study material", "Airport transfer", "Business trip", "Vacation"]
        let symbols = ["$", "€", "£"]
        let currencyNames = ["US Dollar", "Euro", "British Pound"]

        for i in 0..<50 {
            let index = Int.random(in: 0..<titles.count)
            let currencyIndex = Int.random(in: 0..<symbols.count)
            let daysAgo = Int.random(in: 0..<10)
            let date = Calendar.current.date(byAdding: .day, value: -daysAgo, to: Date())!
            let timestamp = Self.timestampFormatter.string(from: date)
            let amount = String(format: "%.2f", Double.random(in: 3.0...150.0))
            let share = String(format: "%.2f", Double.random(in: 1.0...50.0))

            // Cycle through all three statuses for visible demo variety:
            // i % 3 == 0  →  creditor (current user paid)
            // i % 3 == 1  →  beneficiary (someone else paid, current user owes a share)
            // i % 3 == 2  →  neutral (current user is not involved)
            let creditorUuid: String
            var beneficiaryUserUuids: [String]

            switch i % 3 {
            case 0:  // creditor
                creditorUuid = Self.currentUserUuid
                beneficiaryUserUuids = Array(otherUserUuids.prefix(Int.random(in: 1...otherUserUuids.count)))
            case 1:  // beneficiary
                creditorUuid = otherUserUuids[i % otherUserUuids.count]
                beneficiaryUserUuids = [Self.currentUserUuid]
                if Bool.random() {
                    beneficiaryUserUuids.append(otherUserUuids[(i + 1) % otherUserUuids.count])
                }
            default:  // neutral
                creditorUuid = otherUserUuids[i % otherUserUuids.count]
                beneficiaryUserUuids = Array(
                    otherUserUuids.filter {
                        $0 != creditorUuid
                    }
                    .prefix(2))
            }

            let expenseUuid = UUID().uuidString
            let expense = Shared.Expense(
                uuid: expenseUuid,
                timestamp: timestamp,
                title: titles[index],
                description: descriptions[index],
                amount: amount,
                creditorUuid: creditorUuid,
                isRefund: false,
                currency: Shared.Currency(
                    uuid: UUID().uuidString,
                    name: currencyNames[currencyIndex],
                    symbol: symbols[currencyIndex],
                    hasSubunits: true
                ),
                groupUuid: nil
            )

            // TODO: Make sure that the beneficiaries are valid before inserting the expense in the database.
            expenseRepository.insert(expense: expense) { _ in
            }

            for userUuid in beneficiaryUserUuids {
                let beneficiary = Shared.Beneficiary(
                    uuid: UUID().uuidString,
                    expenseUuid: expenseUuid,
                    userUuid: userUuid,
                    share: share
                )
                beneficiaryRepository.insert(beneficiary: beneficiary) { _ in
                }
            }
        }
    }

    deinit {
        cancellable?.cancel()
    }
}
