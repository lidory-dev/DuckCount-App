import SwiftUI

/// What the group screens need from the rest of the app, which implements it on the shared module,
/// as Android's `GroupListCallBacks`, `GroupSettingsCallBacks`, `GroupBalanceCallBacks` and
/// `GroupSettleUpCallBacks` are implemented in its app module: the screens hold no business rule of
/// their own, and this package knows nothing of `Shared`.
///
/// Each stream emits what the device shows as it changes, and stops being followed once the screen
/// reading it is gone. No write throws: each returns why it failed, or nil once it is made, and a
/// write cut short (its task cancelled) may still have landed, so a screen re-reads the group
/// rather than taking it for failed.
@MainActor
public protocol GroupCallBacks: AnyObject {
    /// Longest title a group takes, in UTF-16 units, without the whitespace around it.
    var maxTitleLength: Int { get }

    /// Longest description a group takes, counted as the title is.
    var maxDescriptionLength: Int { get }

    /// The most members a group may have, the user included.
    var maxMembers: Int { get }

    /// The currencies a group can be created with.
    var currencies: [GroupCurrency] { get }

    /// The currency a new group starts in.
    var defaultCurrency: GroupCurrency { get }

    /// The localized name of the currency with the ISO 4217 `code`, "US Dollar" for "USD".
    func currencyDisplayName(code: String) -> String

    /// The user's own UUID.
    func authenticatedUserUuid() -> String?

    /// Every group on the device, those the user is no longer in included, in the order Android
    /// lists them.
    func groups() -> AsyncStream<[GroupSummary]>

    /// The group `id` with its members, or nil once there is no such group on the device: its owner
    /// deleted it from here, the user deleted it from this device, or the server refused its
    /// creation. A screen showing it closes then.
    func group(id: String) -> AsyncStream<GroupDetails?>

    /// How many of the group's expenses this device cannot read yet, which the balances leave out.
    func unreadableExpenseCount(groupId: String) -> AsyncStream<Int>

    /// The user's contacts whose name contains `filter`, whom a group can be created with or a
    /// member added from: never the user, nor anybody whose identity is in doubt.
    func invitableContacts(matching filter: String) -> AsyncStream<[Person]>

    /// The name to show for each of `userUuids` that the device holds one for: a contact's, or the
    /// one a member of a shared group gave their profile. One missing is shown as "Someone else".
    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]>

    /// Where the user stands with `userUuid`, a member of one of their groups.
    func relation(to userUuid: String) -> AsyncStream<MemberRelation>

    /// Who owes whom in the group, or nil when it cannot be worked out.
    func balances(groupId: String) -> AsyncStream<GroupBalances?>

    /// Brings the contacts, the groups and their expenses up to date with the server, in the order
    /// a push would make the device do it. Failures are not reported: the device keeps showing what
    /// it has, and the next refresh tries again.
    func refresh() async

    /// Brings the group `id` and its expenses up to date, its own expenses being read last.
    func refreshGroup(id: String) async

    /// Creates a group with the user and `memberUuids`, in the order the form lists them, its title
    /// and description taken without the whitespace around them.
    func createGroup(title: String, description: String, currency: GroupCurrency, memberUuids: [String]) async -> GroupCreationResult

    /// Gives the group `id` the title and description, taken without the whitespace around them:
    /// its owner only. Returns nil once the server took it.
    func editGroup(id: String, title: String, description: String) async -> GroupEditError?

    /// The versions of the group's title and description, read from the server.
    func history(groupId: String) async -> GroupHistory

    /// Adds `userUuids`, in the order the picker lists them, to the group `groupId`.
    func addMembers(groupId: String, userUuids: [String]) async -> MembershipChangeResult

    /// Removes `userUuid`, never the user, from the group `groupId`.
    func removeMember(groupId: String, userUuid: String) async -> MembershipChangeResult

    /// Leaves the group `groupId`, which stays on the device, read-only. Returns nil once it is
    /// recorded here, whatever the server did yet.
    func leave(groupId: String) async -> GroupFailure?

    /// Deletes the group `groupId` for everybody, on the server first: its owner only.
    func delete(groupId: String) async -> GroupFailure?

    /// Deletes the group `groupId`, which the user is no longer in, from this device only.
    func forget(groupId: String) async -> GroupFailure?

    /// Sends `userUuid`, a member of the group `groupId`, a contact request, the group vouching for
    /// who they are.
    func sendContactRequest(groupId: String, to userUuid: String) async -> MemberContactRequestError?

    /// Records that `debtorUuid` paid the user `amount`, settling what they owed them in the group.
    func recordSettlement(groupId: String, debtorUuid: String, amount: String) async -> GroupFailure?
}

/// The screens of other features the group screens lead to, which the app provides, this package
/// depending on no other.
public struct GroupDestinations {
    /// The group's expenses, shown on its Expenses tab, inside the group's navigation stack: it adds
    /// its own button to add an expense, and leads to their details.
    public var expenses: @MainActor (_ groupId: String) -> AnyView

    /// The contact `userUuid`'s screen, pushed from a member's sheet.
    public var contactDetail: @MainActor (_ userUuid: String) -> AnyView

    /// The contact requests the user received, pushed from a member's sheet.
    public var contactRequests: @MainActor () -> AnyView

    public init(
        expenses: @escaping @MainActor (_ groupId: String) -> AnyView,
        contactDetail: @escaping @MainActor (_ userUuid: String) -> AnyView,
        contactRequests: @escaping @MainActor () -> AnyView
    ) {
        self.expenses = expenses
        self.contactDetail = contactDetail
        self.contactRequests = contactRequests
    }
}
