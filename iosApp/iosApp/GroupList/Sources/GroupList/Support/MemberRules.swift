import Foundation

// What the group screens decide about members, as Android's view models decide it, each rule a
// function of its own for the tests to hold it to: who can be picked, what a group lets the user do
// about its members, and what a member's sheet offers.

// MARK: - Picking members

/// How many members the group has with `selectedUuids` picked besides the `currentMemberCount` it
/// has already: the user alone for a group they create, who is in every group of theirs, so counts
/// against the limit as much as anybody picked.
func memberCount(selectedUuids: Set<String>, currentMemberCount: Int = 1) -> Int {
    selectedUuids.count + currentMemberCount
}

/// Whether `uuid` can be picked, or unpicked: always once picked, so that the selection can still
/// shrink once full, and otherwise while `selectedUuids` leaves room for one more within
/// `maxMembers`, the `currentMemberCount` members included, see `memberCount`.
func isSelectable(uuid: String, selectedUuids: Set<String>, maxMembers: Int, currentMemberCount: Int = 1) -> Bool {
    selectedUuids.contains(uuid) || memberCount(selectedUuids: selectedUuids, currentMemberCount: currentMemberCount) < maxMembers
}

/// `selected` with `uuid` taken out, or added when that keeps the group within `maxMembers`, the
/// `currentMemberCount` members included: past the limit, picking one more does nothing, and the
/// picker shows that row disabled, see `isSelectable`.
func toggledSelection(selected: Set<String>, uuid: String, maxMembers: Int, currentMemberCount: Int = 1) -> Set<String> {
    if selected.contains(uuid) {
        return selected.subtracting([uuid])
    }

    if memberCount(selectedUuids: selected, currentMemberCount: currentMemberCount) < maxMembers {
        return selected.union([uuid])
    }

    return selected
}

/// The members of `memberUuids` the form of a group to create keeps once `selectedUuids` are picked
/// besides the user, `selfUuid`: the user, whatever is picked, and whoever is still picked. Whoever
/// is picked and not listed yet is added to them, the form listing them all by name.
func keptMembers(memberUuids: [String], selfUuid: String, selectedUuids: Set<String>) -> [String] {
    memberUuids.filter { $0 == selfUuid || selectedUuids.contains($0) }
}

/// Who of `users` can be added to the group of `memberUuids`: whoever is not in it yet, in the
/// order they come in.
func invitableUsers(users: [Person], memberUuids: Set<String>) -> [Person] {
    users.filter { !memberUuids.contains($0.id) }
}

/// Why the member picker lists nobody, whatever is searched, as Android's `NobodyToPick`.
enum NobodyToPick: Hashable, Sendable {
    /// The user has no contact they can put in a group.
    case noContacts

    /// Every contact they can put in the group is in it already.
    case allMembers
}

/// Why the picker lists nobody, whatever is searched, for the group of `memberUuids`, none for a
/// group to create: the user has no contact they can put in a group among `contacts`, or every one
/// of them is in it already, see `invitableUsers`. Nil when somebody can be picked: an empty list
/// then means that nobody's name holds what is searched.
func nobodyToPick(contacts: [Person], memberUuids: Set<String>) -> NobodyToPick? {
    if contacts.isEmpty {
        return .noContacts
    }

    if invitableUsers(users: contacts, memberUuids: memberUuids).isEmpty {
        return .allMembers
    }

    return nil
}

/// Who of `picked` is picked still: those the picker lists, `listedUuids`, whatever is searched.
/// Anybody else, a contact a sync forgot meanwhile say, could not be unpicked there, and would be
/// counted and added all the same, refused for it each time the user confirmed.
func listedPicks(picked: Set<String>, listedUuids: Set<String>) -> Set<String> {
    picked.intersection(listedUuids)
}

/// `picked` with `uuid` taken out or added among those of them the picker lists, `listedUuids`, see
/// `listedPicks` and `toggledSelection`, the `currentMemberCount` members counting: whoever else is
/// picked stays so, and counts for nothing until listed again.
func toggledListedSelection(
    picked: Set<String>,
    listedUuids: Set<String>,
    uuid: String,
    maxMembers: Int,
    currentMemberCount: Int
) -> Set<String> {
    let listed = listedPicks(picked: picked, listedUuids: listedUuids)

    return picked
        .subtracting(listed)
        .union(
            toggledSelection(selected: listed, uuid: uuid, maxMembers: maxMembers, currentMemberCount: currentMemberCount)
        )
}

// MARK: - What a group lets the user do

/// What the user can do about the members of a group from its settings, see `memberActions`.
enum MemberActions: Hashable, Sendable {
    /// Nothing: only the group's creator adds and removes members, see
    /// `GroupDetails.canChangeMembers`.
    case none

    /// Adding and removing, once the change waiting on the server is taken, see
    /// `GroupDetails.hasPendingChange`.
    case waiting

    /// Adding and removing.
    case available
}

extension GroupDetails {
    /// What the user can do about the members of the group, see `MemberActions`: nothing in a group
    /// they are no longer in either, which they did not create, its creator never being removed
    /// from it nor leaving it.
    var memberActions: MemberActions {
        if !canChangeMembers {
            return .none
        }

        return hasPendingChange ? .waiting : .available
    }

    /// Whether its settings offer the user to leave it: a group they are still in and did not
    /// create. Its creator never leaves, as nobody else could change its members, and deletes it
    /// instead.
    var isLeavable: Bool {
        canLeave
    }

    /// Whether its settings offer the user to take it off this device: a group they are no longer
    /// in, left, removed from it or found deleted, which nothing but a log-out would take away
    /// otherwise.
    var isRemovableFromDevice: Bool {
        isLeft
    }

    /// Whether its settings offer the history of its title and description: to every member still
    /// in it, its creator included, the server giving it to its current members alone.
    var isHistoryReadable: Bool {
        !isLeft
    }
}

/// Whether the member `userUuid` can be removed by the user `selfUuid`, given the `actions` they
/// have: anybody but them, the group's creator, who never leaves it, as nobody else could change
/// its members.
func isRemovable(userUuid: String, selfUuid: String?, actions: MemberActions) -> Bool {
    actions == .available && userUuid != selfUuid
}

// MARK: - A member's sheet

/// What the sheet of a member offers, below who they are, see `memberSheetActions`.
enum MemberSheetAction: Hashable, Sendable {
    /// Their profile, as one of the user's contacts.
    case viewProfile

    /// The contact requests, where theirs waits for the user's answer.
    case answerRequest

    /// Nothing but telling that the user's request waits for their answer.
    case requestSent

    /// A contact request to them.
    case sendRequest

    /// Their removal from the group, once confirmed, see `isRemovable`.
    case remove
}

/// What the sheet of a member offers, in the order it shows them, given where the user stands with
/// them, `relation`, whether the group vouches for who its members are on this device,
/// `canVouchForMembers`, and whether the member can be removed by the user, `isRemovable`: what
/// their relation calls for first, then their removal.
///
/// No request is sent to them from a group that does not vouch for them: one the user is no longer
/// in, brought back after a log-out, or whose creator's identity is in doubt. Nothing else on this
/// device vouches for who they are, and the request would be refused each time. Their sheet then
/// shows who they are alone, unless they are a contact, or a request either way waits.
func memberSheetActions(relation: MemberRelation, canVouchForMembers: Bool, isRemovable: Bool) -> [MemberSheetAction] {
    var actions: [MemberSheetAction] = []

    switch relation {
    case .contact:
        actions.append(.viewProfile)
    case .requestReceived:
        actions.append(.answerRequest)
    case .requestSent:
        actions.append(.requestSent)
    case .unrelated:
        if canVouchForMembers {
            actions.append(.sendRequest)
        }
    }

    if isRemovable {
        actions.append(.remove)
    }

    return actions
}

/// Where the sheet tells the user stands with a member, `relation` as read, once a request sent
/// from it is out to them, `isRequestTaken`, see `isRequestOut`: sent, while the read still tells
/// nobody the user asked. It catches up a moment after the request returns, recorded as stored, or
/// not while the sheet is open when the server refused it as one was out already that this device
/// kept no record of, a log-out having taken it. `isRequestTaken` is dropped as soon as the read
/// tells anything else, so that it hides nothing since.
func shownRelation(relation: MemberRelation, isRequestTaken: Bool) -> MemberRelation {
    isRequestTaken && relation == .unrelated ? .requestSent : relation
}

/// Whether a request of the user's is out to a member once the one sent from their sheet is
/// answered, `error`, nil when the server took it: so it is too when it was refused as one was out
/// already. Offered again, the request would only spend another of their key packages each time,
/// for the same refusal.
func isRequestOut(error: MemberContactRequestError?) -> Bool {
    error == nil || error == .alreadyPending
}

// MARK: - Debts

/// Whose a debt is, as the user sees it, see `Debt.side(for:)`: what the settle-up tab says of it,
/// and whether they can settle it.
enum DebtSide: Hashable, Sendable {
    /// Owed to the user, who alone records it paid back.
    case owedToUser

    /// Owed by the user, to another member.
    case owedByUser

    /// Owed by one other member to another: the tab shows every debt in the group, not only the
    /// user's.
    case betweenOthers
}

extension Debt {
    /// Whose this debt is for the user `userUuid`, see `DebtSide`.
    func side(for userUuid: String?) -> DebtSide {
        if userUuid == creditorUuid {
            return .owedToUser
        }

        return userUuid == debtorUuid ? .owedByUser : .betweenOthers
    }
}
