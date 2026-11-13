import Foundation

/// A change of the group as a whole that the settings make, which the button it was asked from
/// shows under way.
enum GroupChange: Hashable, Sendable {
    case leave
    case delete

    /// Taking the group, which the user is no longer in, off this device.
    case forget
}

/// A group's settings' state, as Android's `GroupSettingsViewModel` holds it: the group with its
/// members by name, adding and removing them, leaving or deleting the group, and the sheet of the
/// member the user tapped.
@MainActor
final class GroupSettingsViewModel: ObservableObject {
    /// The group as the device holds it, nil until read, and shown only once its members' names are
    /// read as well, see `readGroup`. Kept once the device holds it no more: the group's screen
    /// closes then, and this one with it.
    @Published private(set) var group: GroupDetails?

    /// The names of the members, as they are read: a member the user is put in a group with by
    /// somebody else is named once a group sync reads their profile, which can be after the screen
    /// opened.
    @Published private(set) var names = MemberNames()

    /// True while a change to the members is on its way to the server, the user's leave is being
    /// recorded and sent, or their deletion of the group is being sent: one change at a time, and
    /// neither the picker nor the confirmation it was made from closed until it is answered.
    @Published private(set) var isChanging = false

    /// The member being removed, until the removal is answered.
    @Published private(set) var removingUuid: String?

    /// The change of the group as a whole under way, until it is answered.
    @Published private(set) var change: GroupChange?

    /// Whether the picker the members to add are picked with is open.
    @Published private(set) var isPickingMembers = false

    /// Why the members picked could not be added, told over the picker, which stays open for the
    /// user to pick differently.
    @Published var additionError: MembershipChangeError?

    /// What came of a change once its picker or confirmation closed, if it says anything, see
    /// `MembershipChangeResult.message`: one waiting for the server, or one that failed. A leave or a
    /// deletion that failed is told the same way.
    @Published var notice: MembershipChangeResult?

    // MARK: Member sheet

    /// The member whose sheet is open, until it closes.
    @Published private(set) var openedMemberUuid: String?

    /// Where the user stands with the member whose sheet is open, nil until read.
    @Published private(set) var relation: MemberRelation?

    /// True while a contact request to the member whose sheet is open is on its way to the server,
    /// which keeps the sheet open and its actions disabled until it is answered.
    @Published private(set) var isSendingRequest = false

    /// Why the last request sent from the open sheet was not, told there until the next one is sent
    /// or the sheet closes.
    @Published private(set) var requestError: MemberContactRequestError?

    /// Whether a request sent from the open sheet is out to them, see `isRequestOut`, while where the
    /// user stands with them, as read, does not tell it yet, see `shownRelation`.
    @Published private(set) var isRequestTaken = false

    let groupId: String
    let selfUuid: String?
    let maxMembers: Int

    /// The picker the members to add are picked with.
    let picker: MemberPicker

    private let callBacks: any GroupCallBacks
    private let groupTask = TaskHandle()
    private let relationTask = TaskHandle()
    private var nameFollower: NameFollower?

    /// The group as last read, which shows as `group` once the names of its members are read too, as
    /// Android's settings wait on them: a member listed meanwhile would show with no name, and then
    /// jump to their place in the list once named. Until then, the group shows as it was.
    private var readGroup: GroupDetails?

    init(groupId: String, callBacks: any GroupCallBacks) {
        self.groupId = groupId
        self.callBacks = callBacks
        self.selfUuid = callBacks.authenticatedUserUuid()
        self.maxMembers = callBacks.maxMembers
        self.picker = MemberPicker(callBacks: callBacks, countsListedPicksOnly: true, requiresPick: true)

        nameFollower = NameFollower { [weak self] names in
            self?.names = names
            self?.showReadGroup()
        }

        follow(callBacks.group(id: groupId), owner: self, into: groupTask) { owner, group in
            // Gone: the group's screen closes, and this one with it, showing it as it was meanwhile.
            guard let group else {
                return
            }

            owner.readGroup = group
            owner.followNames()
            owner.showReadGroup()
        }
    }

    /// Shows the group as last read, once the names of all its members are read.
    private func showReadGroup() {
        guard
            let readGroup,
            readGroup != group,
            Set(readGroup.members.map(\.id)).isSubset(of: names.read)
        else {
            return
        }

        group = readGroup
        picker.memberUuids = Set(readGroup.members.map(\.id))
        picker.currentMemberCount = members.count
    }

    /// The group's members, the user first, then the others by name, those with none last.
    var members: [GroupMember] {
        (group?.members ?? [])
            .filter { !$0.id.isBlank }
            .sortedByName(selfUuid: selfUuid, uuidOf: \.id, nameOf: { self.names.name(of: $0.id) })
    }

    /// What the user can do about the members, see `MemberActions`.
    var memberActions: MemberActions {
        group?.memberActions ?? .none
    }

    // MARK: - Adding members

    /// Opens the picker, with nobody picked.
    func beginAddingMembers() {
        guard memberActions == .available else {
            return
        }

        additionError = nil
        picker.open()
        isPickingMembers = true
    }

    /// Closes the picker, unless the members picked are being added: what comes of it is told there.
    func cancelAddingMembers() {
        guard !isChanging else {
            return
        }

        closePicker()
    }

    /// Once the picker's sheet is gone, however it closed: what it picked and searched goes with it,
    /// and the contacts are read no more. Not before, for the sheet not to empty as it slides away.
    func endAddingMembers() {
        picker.close()
    }

    /// Whether the picker can be confirmed: members are added one at least, see
    /// `MemberPicker.canConfirm`, which the picker's Confirm button reads.
    var canAddMembers: Bool {
        !isChanging && picker.canConfirm
    }

    /// Adds whoever is picked to the group, and closes the picker once the server took them, or
    /// will. The picker stays open on a failure, which it tells.
    func addMembers() {
        guard canAddMembers else {
            return
        }

        isChanging = true
        additionError = nil

        let userUuids = picker.orderedSelection

        Task {
            let result = await callBacks.addMembers(groupId: groupId, userUuids: userUuids)

            isChanging = false

            if case .failed(let error) = result {
                additionError = error
            } else {
                closePicker()
                notice = result.message == nil ? nil : result
            }
        }
    }

    /// Closes the picker's sheet, which keeps showing what it picked as it slides away, see
    /// `endAddingMembers`.
    private func closePicker() {
        isPickingMembers = false
        additionError = nil
    }

    // MARK: - Removing a member

    /// Removes the member `userUuid`, never the user, once they confirmed it.
    func remove(userUuid: String) {
        guard !isChanging, isRemovable(userUuid: userUuid, selfUuid: selfUuid, actions: memberActions) else {
            return
        }

        isChanging = true
        removingUuid = userUuid

        Task {
            let result = await callBacks.removeMember(groupId: groupId, userUuid: userUuid)

            isChanging = false
            removingUuid = nil
            notice = result.message == nil ? nil : result
        }
    }

    // MARK: - Leaving and deleting

    /// Takes the user out of the group, once they confirmed it. Nothing more once it went through:
    /// the group, read again, is left, and the screen shows it read-only from then on.
    func leave() {
        guard group?.isLeavable == true else {
            return
        }

        changeGroup(.leave)
    }

    /// Deletes the group for everybody, once its owner confirmed it: the device then holds it no
    /// more, and its screens close. Not while a change of it waits on the server.
    func delete() {
        guard let group, group.canDelete, !group.hasPendingChange else {
            return
        }

        changeGroup(.delete)
    }

    /// Takes the group, which the user is no longer in, off this device, once they confirmed it, as
    /// `delete` does.
    func forget() {
        guard group?.isRemovableFromDevice == true else {
            return
        }

        changeGroup(.forget)
    }

    private func changeGroup(_ change: GroupChange) {
        guard !isChanging else {
            return
        }

        isChanging = true
        self.change = change

        Task {
            let failure: GroupFailure?

            switch change {
            case .leave:
                failure = await callBacks.leave(groupId: groupId)
            case .delete:
                failure = await callBacks.delete(groupId: groupId)
            case .forget:
                failure = await callBacks.forget(groupId: groupId)
            }

            isChanging = false
            self.change = nil

            if let failure {
                notice = .failed(.failure(failure))
            }
        }
    }

    // MARK: - Member sheet

    /// Opens the sheet of `userUuid`, never the user's, and reads where the user stands with them.
    func openMember(userUuid: String) {
        guard userUuid != selfUuid else {
            return
        }

        openedMemberUuid = userUuid
        relation = nil
        requestError = nil
        isRequestTaken = false

        follow(callBacks.relation(to: userUuid), owner: self, into: relationTask) { owner, relation in
            // Only until the read tells anything but nobody the user asked, so that whatever
            // changes after shows as read: they accept and then remove the user, say.
            if owner.isRequestTaken, relation != .unrelated {
                owner.isRequestTaken = false
            }

            owner.relation = relation
        }

        // Followed with the members, for the sheet to name them, even once the group lists them no
        // more, a sync reading their removal with the sheet open.
        followNames()
    }

    /// Closes the open sheet, unless a contact request is being sent from it: what comes of it is
    /// told there. What it told goes with it.
    func closeMember() {
        guard !isSendingRequest else {
            return
        }

        openedMemberUuid = nil
        relation = nil
        requestError = nil
        isRequestTaken = false
        relationTask.replace(with: nil)
        followNames()
    }

    /// What the open sheet offers, in the order it shows them, nil until where the user stands with
    /// the member is read, see `memberSheetActions`.
    var memberSheetActions: [MemberSheetAction]? {
        guard let userUuid = openedMemberUuid, let relation, let group else {
            return nil
        }

        return GroupList.memberSheetActions(
            relation: shownRelation(relation: relation, isRequestTaken: isRequestTaken),
            canVouchForMembers: group.canVouchForMembers,
            isRemovable: isRemovable(userUuid: userUuid, selfUuid: selfUuid, actions: memberActions)
        )
    }

    /// Asks the member whose sheet is open to become a contact, telling why not in the sheet, which
    /// stays open either way.
    func sendContactRequest() {
        guard !isSendingRequest, let userUuid = openedMemberUuid else {
            return
        }

        isSendingRequest = true
        requestError = nil

        Task {
            let error = await callBacks.sendContactRequest(groupId: groupId, to: userUuid)

            // Before the spinner goes, so that the sheet never offers the request again meanwhile.
            isRequestTaken = isRequestOut(error: error)
            requestError = error
            isSendingRequest = false
        }
    }

    // MARK: - Names

    /// Follows the names of the group's members as last read, for it to show once they are, see
    /// `readGroup`, and of the member whose sheet is open.
    private func followNames() {
        let memberUuids = (readGroup?.members ?? []).map(\.id) + [openedMemberUuid].compactMap { $0 }

        nameFollower?.follow(Set(memberUuids), callBacks: callBacks)
    }
}
