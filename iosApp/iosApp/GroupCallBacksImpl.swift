import Foundation
import GroupList
import Shared

/// The group screens' callbacks, run by the shared module's `GroupManagement`, as Android's
/// `GroupListCallBacksImpl`, `GroupSettingsCallBacksImpl`, `GroupBalanceCallBacksImpl` and
/// `GroupSettleUpCallBacksImpl` run them in its app module.
///
/// Many names exist on both sides, `GroupFailure`, `MemberRelation`, `GroupHistory`, `GroupMember`
/// among them, so every type here is qualified with its module.
@MainActor
final class GroupCallBacksImpl: GroupCallBacks {
    private let management: GroupManagement

    init(management: GroupManagement = KoinHelper().getGroupManagement()) {
        self.management = management
    }

    var maxTitleLength: Int {
        Int(management.maxTitleLength)
    }

    var maxDescriptionLength: Int {
        Int(management.maxDescriptionLength)
    }

    var maxMembers: Int {
        Int(management.maxMembers)
    }

    var currencies: [GroupList.GroupCurrency] {
        management.currencies.map(\.groupCurrency)
    }

    var defaultCurrency: GroupList.GroupCurrency {
        management.defaultCurrency.groupCurrency
    }

    func currencyDisplayName(code: String) -> String {
        management.currencyDisplayName(code: code)
    }

    func authenticatedUserUuid() -> String? {
        management.authenticatedUserUuid()
    }

    func groups() -> AsyncStream<[GroupList.GroupSummary]> {
        stream(management.groups()) { value in
            (value as? [Shared.Group] ?? []).map { group in
                GroupList.GroupSummary(
                    id: group.uuid,
                    title: group.title,
                    description: group.description_,
                    isLeft: group.isLeft,
                    isDeleted: group.isDeleted
                )
            }
        }
    }

    func group(id: String) -> AsyncStream<GroupList.GroupDetails?> {
        stream(management.group(uuid: id)) { value in
            (value as? Shared.GroupWithMembers)?.groupDetails
        }
    }

    func unreadableExpenseCount(groupId: String) -> AsyncStream<Int> {
        stream(management.unreadableExpenseCount(groupUuid: groupId)) { value in
            (value as? KotlinInt)?.intValue ?? 0
        }
    }

    func invitableContacts(matching filter: String) -> AsyncStream<[GroupList.Person]> {
        stream(management.invitableContacts(filter: filter)) { value in
            (value as? [Shared.User] ?? []).map { GroupList.Person(id: $0.uuid, fullName: $0.fullName) }
        }
    }

    func fullNames(of userUuids: Set<String>) -> AsyncStream<[String: String]> {
        stream(management.fullNames(userUuids: Array(userUuids))) { value in
            value as? [String: String] ?? [:]
        }
    }

    func relation(to userUuid: String) -> AsyncStream<GroupList.MemberRelation> {
        stream(management.relation(userUuid: userUuid)) { value in
            (value as? Shared.MemberRelation)?.memberRelation ?? .unrelated
        }
    }

    func balances(groupId: String) -> AsyncStream<GroupList.GroupBalances?> {
        stream(management.balances(groupUuid: groupId)) { value in
            guard let snapshot = value as? Shared.GroupBalancesSnapshot else {
                return nil
            }

            return GroupList.GroupBalances(
                netAmounts: snapshot.netAmounts,
                memberUuids: Set(snapshot.memberUuids),
                debts: snapshot.debts.map {
                    GroupList.Debt(debtorUuid: $0.debtorUuid, creditorUuid: $0.creditorUuid, amount: $0.amount)
                },
                currency: snapshot.currency.groupCurrency
            )
        }
    }

    func refresh() async {
        // Kotlin only throws its cancellation over to Swift.
        try? await management.refresh()
    }

    func refreshGroup(id: String) async {
        try? await management.refreshGroup(groupUuid: id)
    }

    func createGroup(
        title: String,
        description: String,
        currency: GroupList.GroupCurrency,
        memberUuids: [String]
    ) async -> GroupList.GroupCreationResult {
        await BackgroundTime.whileFinishing("create group") { [management] in
            do {
                let outcome = try await management.create(
                    title: title,
                    description: description,
                    currency: currency.sharedCurrency,
                    memberUuids: memberUuids
                )

                switch outcome {
                case let created as GroupCreationOutcomeCreated:
                    return .created(groupId: created.groupUuid)
                case let refused as GroupCreationOutcomeRefused:
                    return .failed(.members(refused.refusal.memberRefusal, names: refused.fullNames))
                case let failed as GroupCreationOutcomeFailed:
                    return .failed(.failure(failed.failure.groupFailure))
                default:
                    return .failed(.failure(.unknown))
                }
            } catch {
                return .failed(.failure(.unknown))
            }
        }
    }

    func editGroup(id: String, title: String, description: String) async -> GroupList.GroupEditError? {
        await BackgroundTime.whileFinishing("edit group") { [management] in
            do {
                return try await management.edit(uuid: id, title: title, description: description)?.editError
            } catch {
                return .failure(.unknown)
            }
        }
    }

    func history(groupId: String) async -> GroupList.GroupHistory {
        do {
            let outcome = try await management.history(uuid: groupId)

            if let read = outcome as? GroupHistoryOutcomeVersions {
                return .versions(
                    read.versions.map { version in
                        GroupList.GroupVersion(
                            revision: version.revision,
                            title: version.title,
                            description: version.description_,
                            isCreation: version.isCreation,
                            isCurrent: version.isCurrent,
                            revisedAt: Date(timeIntervalSince1970: TimeInterval(version.revisedAt) / 1000)
                        )
                    }
                )
            }

            return .unavailable((outcome as? GroupHistoryOutcomeUnavailable)?.failure.historyError ?? .failure(.unknown))
        } catch {
            return .unavailable(.failure(.unknown))
        }
    }

    func addMembers(groupId: String, userUuids: [String]) async -> GroupList.MembershipChangeResult {
        await BackgroundTime.whileFinishing("add members") { [management] in
            do {
                return try await management.addMembers(groupUuid: groupId, userUuids: userUuids).membershipChangeResult
            } catch {
                return .failed(.failure(.unknown))
            }
        }
    }

    func removeMember(groupId: String, userUuid: String) async -> GroupList.MembershipChangeResult {
        await BackgroundTime.whileFinishing("remove member") { [management] in
            do {
                return try await management.removeMember(groupUuid: groupId, userUuid: userUuid).membershipChangeResult
            } catch {
                return .failed(.failure(.unknown))
            }
        }
    }

    func leave(groupId: String) async -> GroupList.GroupFailure? {
        await BackgroundTime.whileFinishing("leave group") { [management] in
            do {
                return try await management.leave(groupUuid: groupId)?.groupFailure
            } catch {
                return .unknown
            }
        }
    }

    func delete(groupId: String) async -> GroupList.GroupFailure? {
        await BackgroundTime.whileFinishing("delete group") { [management] in
            do {
                return try await management.delete(groupUuid: groupId)?.groupFailure
            } catch {
                return .unknown
            }
        }
    }

    func forget(groupId: String) async -> GroupList.GroupFailure? {
        await BackgroundTime.whileFinishing("forget group") { [management] in
            do {
                return try await management.forget(groupUuid: groupId)?.groupFailure
            } catch {
                return .unknown
            }
        }
    }

    func sendContactRequest(groupId: String, to userUuid: String) async -> GroupList.MemberContactRequestError? {
        await BackgroundTime.whileFinishing("send contact request") { [management] in
            do {
                return try await management.sendContactRequest(groupUuid: groupId, userUuid: userUuid)?.requestError
            } catch {
                return .unknown
            }
        }
    }

    func recordSettlement(groupId: String, debtorUuid: String, amount: String) async -> GroupList.GroupFailure? {
        await BackgroundTime.whileFinishing("record settlement") { [management] in
            do {
                return try await management.recordSettlement(groupUuid: groupId, debtorUuid: debtorUuid, amount: amount)?.groupFailure
            } catch {
                return .unknown
            }
        }
    }
}

extension Shared.Currency {
    fileprivate var groupCurrency: GroupList.GroupCurrency {
        // Its name is its ISO 4217 code, which every device names it by.
        GroupList.GroupCurrency(id: uuid, code: name, symbol: symbol, hasSubunits: hasSubunits)
    }
}

extension GroupList.GroupCurrency {
    fileprivate var sharedCurrency: Shared.Currency {
        Shared.Currency(uuid: id, name: code, symbol: symbol, hasSubunits: hasSubunits)
    }
}

extension Shared.GroupWithMembers {
    fileprivate var groupDetails: GroupList.GroupDetails {
        GroupList.GroupDetails(
            id: uuid,
            title: title,
            description: description_,
            currency: currency.groupCurrency,
            members: groupMembers.map { GroupList.GroupMember(id: $0.userUuid, isOwner: $0.isAdmin) },
            isLeft: isLeft,
            isDeleted: isDeleted,
            hasPendingChange: hasPendingChange,
            canChangeMembers: canChangeMembers,
            canEdit: canEdit,
            canVouchForMembers: canVouchForMembers,
            canLeave: canLeave,
            canDelete: canDelete
        )
    }
}

extension Shared.MemberChangeOutcome {
    fileprivate var membershipChangeResult: GroupList.MembershipChangeResult {
        switch self {
        case is MemberChangeOutcomeMade:
            .made
        case is MemberChangeOutcomePending:
            .pending
        case let refused as MemberChangeOutcomeRefused:
            .failed(.members(refused.refusal.memberRefusal, names: refused.fullNames))
        case let failed as MemberChangeOutcomeFailed:
            .failed(.failure(failed.failure.groupFailure))
        default:
            .failed(.failure(.unknown))
        }
    }
}

// Kotlin enums cross over as classes, so the last entry of each stands in for the default Swift
// asks for.

extension Shared.GroupFailure {
    fileprivate var groupFailure: GroupList.GroupFailure {
        switch self {
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        case .groupLeft: .groupLeft
        case .rateLimited: .rateLimited
        case .awaitingOwner: .awaitingOwner
        default: .unknown
        }
    }
}

extension Shared.MemberRefusal {
    fileprivate var memberRefusal: GroupList.MemberRefusal {
        switch self {
        case .notContacts: .notContacts
        case .identityChanged: .identityChanged
        case .unreachable: .unreachable
        default: .deleted
        }
    }
}

extension Shared.GroupEditFailure {
    fileprivate var editError: GroupList.GroupEditError {
        switch self {
        case .changePending: .changePending
        case .outdated: .outdated
        case .unchanged: .unchanged
        case .noInternet: .failure(.noInternet)
        case .timeout: .failure(.timeout)
        case .serverUnreachable: .failure(.serverUnreachable)
        default: .failure(.unknown)
        }
    }
}

extension Shared.GroupHistoryFailure {
    fileprivate var historyError: GroupList.GroupHistoryError {
        switch self {
        case .deviceLocked: .deviceLocked
        case .unreadable: .unreadable
        case .noInternet: .failure(.noInternet)
        case .timeout: .failure(.timeout)
        case .serverUnreachable: .failure(.serverUnreachable)
        default: .failure(.unknown)
        }
    }
}

extension Shared.MemberRelation {
    fileprivate var memberRelation: GroupList.MemberRelation {
        switch self {
        case .contact: .contact
        case .requestReceived: .requestReceived
        case .requestSent: .requestSent
        default: .unrelated
        }
    }
}

extension Shared.MemberContactRequestFailure {
    fileprivate var requestError: GroupList.MemberContactRequestError {
        switch self {
        case .notFound: .notFound
        case .alreadyContact: .alreadyContact
        case .alreadyPending: .alreadyPending
        case .rateLimited: .rateLimited
        case .identityChanged: .identityChanged
        case .notInGroup: .notInGroup
        case .blocked: .blocked
        case .noInternet: .noInternet
        case .timeout: .timeout
        case .serverUnreachable: .serverUnreachable
        default: .unknown
        }
    }
}
