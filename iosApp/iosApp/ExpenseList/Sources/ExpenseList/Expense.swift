import Foundation

public struct Expense: Identifiable, Hashable {
    public let id: String
    public let timestamp: String
    public let title: String
    public let description: String
    public let amount: String
    public let creditorUuid: String
    public let isSettlement: Bool
    public let currency: Currency
    public let status: ExpenseStatus

    public init(
        id: String,
        timestamp: String = "",
        title: String = "",
        description: String = "",
        amount: String = "",
        creditorUuid: String = "",
        isSettlement: Bool = false,
        currency: Currency,
        status: ExpenseStatus = .beneficiary
    ) {
        self.id = id
        self.timestamp = timestamp
        self.title = title
        self.description = description
        self.amount = amount
        self.creditorUuid = creditorUuid
        self.isSettlement = isSettlement
        self.currency = currency
        self.status = status
    }
}
