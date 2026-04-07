public struct ExpenseSection: Identifiable {
    public let id: String
    public let displayDate: String
    public let expenses: [Expense]

    public init(id: String, displayDate: String, expenses: [Expense]) {
        self.id = id
        self.displayDate = displayDate
        self.expenses = expenses
    }
}
