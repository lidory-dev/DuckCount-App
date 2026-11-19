import ContactList
import GroupList
import SwiftUI

/// What a tapped notification opens over its tab, as Android's notifications name it in the intent
/// they open: the requests waiting for an answer, a contact who accepted one, an expense, or a group
/// and one of its expenses.
enum NotificationOpening: Equatable {
    case contactRequests
    case contact(String)
    case expense(String)
    case group(String, expenseId: String?)

    /// How a notification carries it, in its `userInfo`.
    var userInfo: [String: String] {
        switch self {
        case .contactRequests: [Self.contactRequestsKey: "true"]
        case .contact(let userUuid): [Self.contactKey: userUuid]
        case .expense(let expenseId): [Self.expenseKey: expenseId]
        case .group(let groupId, let expenseId): [Self.groupKey: groupId].merging(expenseId.map { [Self.expenseKey: $0] } ?? [:]) { $1 }
        }
    }

    /// What `userInfo` says to open, or nil when it says nothing.
    init?(userInfo: [AnyHashable: Any]) {
        if let groupId = userInfo[Self.groupKey] as? String {
            self = .group(groupId, expenseId: userInfo[Self.expenseKey] as? String)
        } else if let expenseId = userInfo[Self.expenseKey] as? String {
            self = .expense(expenseId)
        } else if let userUuid = userInfo[Self.contactKey] as? String {
            self = .contact(userUuid)
        } else if userInfo[Self.contactRequestsKey] != nil {
            self = .contactRequests
        } else {
            return nil
        }
    }

    private static let contactRequestsKey = "openContactRequests"
    private static let contactKey = "openContact"
    private static let expenseKey = "openExpense"
    private static let groupKey = "openGroup"
}

/// What a tapped notification asks each tab to open, which the tab's screen hands its package, and
/// the package clears once open: kept here meanwhile, for the tap that launched the app to reach a
/// screen not built yet.
@MainActor
final class NotificationRoute: ObservableObject {
    static let shared = NotificationRoute()

    @Published var expense: String?
    @Published var group: GroupOpening?
    @Published var contacts: ContactListOpening?

    private init() {}

    func open(_ opening: NotificationOpening) {
        switch opening {
        case .contactRequests: contacts = .requests
        case .contact(let userUuid): contacts = .contact(userUuid)
        case .expense(let expenseId): expense = expenseId
        case .group(let groupId, let expenseId): group = GroupOpening(groupId: groupId, expenseId: expenseId)
        }
    }

    /// Forgets what nothing opened, as the session ends: it was the last session's.
    func clear() {
        expense = nil
        group = nil
        contacts = nil
    }
}
