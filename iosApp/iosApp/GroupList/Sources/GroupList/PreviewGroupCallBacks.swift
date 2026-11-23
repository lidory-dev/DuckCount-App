import SwiftUI

/// Answers the group screens with a few groups made up for previews, and takes every write as made.
@MainActor
final class PreviewGroupCallBacks: GroupCallBacks {
    let maxTitleLength = 50
    let maxDescriptionLength = 1000
    let maxMembers = 100

    let currencies = [
        GroupCurrency(id: "eur", code: "EUR", symbol: "€", hasSubunits: true),
        GroupCurrency(id: "usd", code: "USD", symbol: "$", hasSubunits: true),
        GroupCurrency(id: "jpy", code: "JPY", symbol: "¥", hasSubunits: false),
    ]

    var defaultCurrency: GroupCurrency {
        currencies[0]
    }

    /// Screens of other features, which previews stand in for.
    static var destinations: GroupDestinations {
        GroupDestinations(
            expenses: { _, _ in AnyView(Text(verbatim: "Expenses")) },
            contactDetail: { userUuid in AnyView(Text(verbatim: userUuid)) },
            contactRequests: { AnyView(Text(verbatim: "Contact requests")) }
        )
    }

    private let names = ["me": "Me", "ada": "Ada Lovelace", "bob": "Bob", "carol": "Carol"]

    private var details: [GroupDetails] {
        [
            GroupDetails(
                id: "ski",
                title: "Ski trip",
                description: "Chalet and lift passes",
                currency: currencies[0],
                members: [GroupMember(id: "me", isOwner: true), GroupMember(id: "ada", isOwner: false), GroupMember(id: "bob", isOwner: false)],
                isLeft: false,
                isDeleted: false,
                hasPendingChange: false,
                canChangeMembers: true,
                canEdit: true,
                canVouchForMembers: true,
                canLeave: false,
                canDelete: true
            ),
            GroupDetails(
                id: "flat",
                title: "Flat share",
                description: "",
                currency: currencies[0],
                members: [GroupMember(id: "carol", isOwner: true), GroupMember(id: "me", isOwner: false)],
                isLeft: true,
                isDeleted: true,
                hasPendingChange: false,
                canChangeMembers: false,
                canEdit: false,
                canVouchForMembers: false,
                canLeave: false,
                canDelete: false
            ),
        ]
    }

    func currencyDisplayName(code: String) -> String {
        Locale.current.localizedString(forCurrencyCode: code) ?? code
    }

    func authenticatedUserUuid() -> String? {
        "me"
    }

    func groups() -> AsyncStream<[GroupSummary]> {
        let summaries = details.map {
            GroupSummary(id: $0.id, title: $0.title, description: $0.description, isLeft: $0.isLeft, isDeleted: $0.isDeleted)
        }

        return AsyncStream { $0.yield(summaries) }
    }

    func group(id: String) -> AsyncStream<GroupDetails?> {
        let group = details.first { $0.id == id }

        return AsyncStream { $0.yield(group) }
    }

    func unreadableExpenseCount(groupId: String) -> AsyncStream<Int> {
        AsyncStream { $0.yield(groupId == "ski" ? 1 : 0) }
    }

    func invitableContacts(matching filter: String) -> AsyncStream<[Person]> {
        let filter = filter.trimmingCharacters(in: .whitespacesAndNewlines)
        let people = names
            .filter { $0.key != "me" }
            .map { Person(id: $0.key, fullName: $0.value) }
            .filter { filter.isEmpty || $0.fullName.localizedCaseInsensitiveContains(filter) }

        return AsyncStream { $0.yield(people) }
    }

    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]> {
        let known = names.filter { userUuids.contains($0.key) }

        return AsyncStream { $0.yield(known) }
    }

    func relation(to userUuid: String) -> AsyncStream<MemberRelation> {
        AsyncStream { $0.yield(userUuid == "ada" ? .contact : .unrelated) }
    }

    func balances(groupId: String) -> AsyncStream<GroupBalances?> {
        let balances = GroupBalances(
            netAmounts: ["me": "30.00", "ada": "-20.00", "bob": "-10.00", "dave": "0.00"],
            memberUuids: ["me", "ada", "bob"],
            debts: [
                Debt(debtorUuid: "ada", creditorUuid: "me", amount: "20.00"),
                Debt(debtorUuid: "bob", creditorUuid: "me", amount: "10.00"),
            ],
            currency: GroupCurrency(id: "eur", code: "EUR", symbol: "€", hasSubunits: true)
        )

        return AsyncStream { $0.yield(balances) }
    }

    func refresh() async {}

    func refreshGroup(id: String) async {}

    func createGroup(title: String, description: String, currency: GroupCurrency, memberUuids: [String]) async -> GroupCreationResult {
        .created(groupId: "ski")
    }

    func editGroup(id: String, title: String, description: String) async -> GroupEditError? {
        nil
    }

    func history(groupId: String) async -> GroupHistory {
        .versions([
            GroupVersion(revision: 2, title: "Ski trip", description: "Chalet and lift passes", isCreation: false, isCurrent: true, revisedAt: .now),
            GroupVersion(revision: 1, title: "Ski", description: "", isCreation: true, isCurrent: false, revisedAt: .now.addingTimeInterval(-86_400)),
        ])
    }

    func addMembers(groupId: String, userUuids: [String]) async -> MembershipChangeResult {
        .made
    }

    func removeMember(groupId: String, userUuid: String) async -> MembershipChangeResult {
        .pending
    }

    func leave(groupId: String) async -> GroupFailure? {
        nil
    }

    func delete(groupId: String) async -> GroupFailure? {
        .noInternet
    }

    func forget(groupId: String) async -> GroupFailure? {
        nil
    }

    func sendContactRequest(groupId: String, to userUuid: String) async -> MemberContactRequestError? {
        nil
    }

    func recordSettlement(groupId: String, debtorUuid: String, amount: String) async -> GroupFailure? {
        nil
    }
}
