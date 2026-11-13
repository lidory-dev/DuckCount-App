import Foundation

/// The member picker's state, which the group form and the group's settings each hold one of, as
/// Android's `CreateGroupViewModel` and `GroupSettingsViewModel` hold theirs: whom it lists, as
/// searched, whom it has picked, and how far the group lets it go. It reads the contacts only while
/// open.
@MainActor
final class MemberPicker: ObservableObject {
    /// The search field's text, as typed: the shared module trims it.
    @Published private(set) var searchQuery = ""

    /// The contacts whose name holds what is searched, as the shared module lists them, nil until
    /// read.
    @Published private(set) var found: [Person]?

    /// The contacts the user can put in a group, whatever is searched, nil until read: what tells a
    /// search that found nobody from a user with nobody to pick, see `nobodyToPick`.
    @Published private(set) var contacts: [Person]?

    /// Who is picked, as picked: see `selectedUuids` for who counts.
    @Published private(set) var picked: Set<String> = []

    /// The group's members, whom the picker does not list: none for a group to create, the user
    /// being nobody's contact of their own.
    @Published var memberUuids: Set<String> = []

    /// How many members the group has already, who count against `maxMembers`: the user alone for
    /// a group to create.
    @Published var currentMemberCount = 1

    let maxMembers: Int

    /// Whether whoever is picked and no longer listed counts for nothing, as when adding members to
    /// a group that exists, see `listedPicks`. A group to create keeps them, as Android's form
    /// does: the server says who of them cannot be put in it.
    let countsListedPicksOnly: Bool

    /// Whether the picker is confirmed only with somebody picked, as when adding members to a group
    /// that exists, see `canConfirm`. A group to create is confirmed with nobody picked too, which
    /// leaves the user alone in its form.
    let requiresPick: Bool

    private let callBacks: any GroupCallBacks
    private let foundTask = TaskHandle()
    private let contactsTask = TaskHandle()

    init(callBacks: any GroupCallBacks, countsListedPicksOnly: Bool, requiresPick: Bool) {
        self.callBacks = callBacks
        self.maxMembers = callBacks.maxMembers
        self.countsListedPicksOnly = countsListedPicksOnly
        self.requiresPick = requiresPick
    }

    /// Whether the picker can be confirmed as it stands, see `requiresPick`. Read by the picker's
    /// own view, which follows the picker: worked out by the screen that shows the picker, it would
    /// be worked out again only when that screen's state changes, and not as the user picks.
    var canConfirm: Bool {
        !requiresPick || !selectedUuids.isEmpty
    }

    /// The contacts listed for what is searched, by name: whoever is not in the group yet.
    var users: [Person] {
        invitableUsers(users: found ?? [], memberUuids: memberUuids)
            .sortedByName(selfUuid: nil, uuidOf: \.id, nameOf: \.fullName)
    }

    /// Why the picker lists nobody whatever is searched, nil while somebody can be picked, and
    /// while the contacts are still being read.
    var nobodyToPick: NobodyToPick? {
        contacts.flatMap { GroupList.nobodyToPick(contacts: $0, memberUuids: memberUuids) }
    }

    /// Who the picker lists whatever is searched.
    var listedUuids: Set<String> {
        Set(invitableUsers(users: contacts ?? [], memberUuids: memberUuids).map(\.id))
    }

    /// Who is picked and counts, see `countsListedPicksOnly`.
    var selectedUuids: Set<String> {
        countsListedPicksOnly ? listedPicks(picked: picked, listedUuids: listedUuids) : picked
    }

    /// `selectedUuids` in the order the picker lists them whatever is searched, which is the order
    /// they are added in.
    var orderedSelection: [String] {
        let selected = selectedUuids
        let listed = invitableUsers(users: contacts ?? [], memberUuids: memberUuids)
            .sortedByName(selfUuid: nil, uuidOf: \.id, nameOf: \.fullName)
            .map(\.id)
            .filter { selected.contains($0) }

        // Anybody picked and not listed comes last, a group to create keeping them.
        return listed + selected.subtracting(listed).sorted()
    }

    /// How many members the group would have with whoever is picked.
    var memberCount: Int {
        GroupList.memberCount(selectedUuids: selectedUuids, currentMemberCount: currentMemberCount)
    }

    /// Whether `uuid` can be picked or unpicked, see `isSelectable`.
    func isSelectable(_ uuid: String) -> Bool {
        GroupList.isSelectable(
            uuid: uuid,
            selectedUuids: selectedUuids,
            maxMembers: maxMembers,
            currentMemberCount: currentMemberCount
        )
    }

    func isPicked(_ uuid: String) -> Bool {
        selectedUuids.contains(uuid)
    }

    /// Picks `uuid`, or unpicks them, within the limit.
    func toggle(_ uuid: String) {
        if countsListedPicksOnly {
            picked = toggledListedSelection(
                picked: picked,
                listedUuids: listedUuids,
                uuid: uuid,
                maxMembers: maxMembers,
                currentMemberCount: currentMemberCount
            )
        } else {
            picked = toggledSelection(selected: picked, uuid: uuid, maxMembers: maxMembers, currentMemberCount: currentMemberCount)
        }
    }

    func search(_ query: String) {
        guard query != searchQuery else {
            return
        }

        searchQuery = query
        searchContacts()
    }

    /// Opens the picker with `picked` picked and nothing searched, and reads the contacts from now
    /// on.
    func open(picked: Set<String> = []) {
        self.picked = picked
        searchQuery = ""

        follow(callBacks.invitableContacts(matching: ""), owner: self, into: contactsTask) { owner, contacts in
            owner.contacts = contacts
        }

        searchContacts()
    }

    /// Closes the picker: what was picked and searched goes with it, and the contacts are read no
    /// more. Once its sheet is gone, not as it closes: it would empty as it slides away.
    func close() {
        foundTask.replace(with: nil)
        contactsTask.replace(with: nil)
        picked = []
        searchQuery = ""
        found = nil
        contacts = nil
    }

    private func searchContacts() {
        follow(callBacks.invitableContacts(matching: searchQuery), owner: self, into: foundTask) { owner, found in
            owner.found = found
        }
    }
}
