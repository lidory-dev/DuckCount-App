import Foundation

/// A group's title and description, as a group being edited shows them.
struct GroupInformation: Hashable, Sendable {
    let title: String
    let description: String
}

/// The group form as it stands, as Android's `CreateGroupState.Content` holds it: a group to
/// create, or one being edited.
struct GroupForm: Equatable {
    /// The title field's text, as typed: the group is checked and saved without the whitespace
    /// around it, see `trimmedTitle`. Trimmed as it is typed, a space typed after a word would be
    /// swallowed.
    var title = ""

    /// The description field's text, as typed, as `title` is.
    var description = ""

    var currency: GroupCurrency

    /// Who the group to create is made of, the user among them: nobody for a group being edited,
    /// whose members are changed from its settings, by its creator alone.
    var memberUuids: [String] = []

    /// The title and description the group being edited shows now, which the form saves nothing but
    /// a change of, see `canConfirm`: read again each time they change, as when an edit refused as
    /// outdated shows the version the server holds, or a sync an earlier edit of the user's whose
    /// answer was lost. Nil for a group to create.
    var shown: GroupInformation?

    var trimmedTitle: String {
        title.trimmed
    }

    var trimmedDescription: String {
        description.trimmed
    }

    /// How long the title is as it is saved: in UTF-16 units, the unit every text limit of the app
    /// is counted in, as Android counts it and a receiving device checks it.
    var titleLength: Int {
        trimmedTitle.utf16.count
    }

    /// How long the description is as it is saved, counted as the title is.
    var descriptionLength: Int {
        trimmedDescription.utf16.count
    }

    /// Whether the form holds a group to confirm, as Android's `canConfirm` tells it: a title and a
    /// description within the limits, as they are saved, and for a group to create, the user and
    /// somebody else in it. A group being edited is confirmed only with a title or a description
    /// other than those it shows: an edit that changes nothing would still wake every other member,
    /// and take a place in the group's history.
    func canConfirm(maxTitleLength: Int, maxDescriptionLength: Int) -> Bool {
        let isChanged: Bool

        if let shown {
            isChanged = trimmedTitle != shown.title || trimmedDescription != shown.description
        } else {
            isChanged = memberUuids.count >= 2
        }

        return isChanged
            && (1...maxTitleLength).contains(titleLength)
            && (0...maxDescriptionLength).contains(descriptionLength)
    }
}

/// The group form's state, a group to create or one being edited, as Android's
/// `CreateGroupViewModel` holds it.
@MainActor
final class GroupFormViewModel: ObservableObject {
    /// What the form is for.
    enum Mode: Equatable {
        case create
        case edit(groupId: String)
    }

    @Published private(set) var form: GroupForm

    /// Whether the group being edited has yet to be read into the form.
    @Published private(set) var isBeingRead: Bool

    /// Whether the user typed in the title field, which says a title is missing only once they did:
    /// a new form does not open on an error.
    @Published private(set) var isTitleEdited = false

    /// True while the group is being created, or what the user edited of it saved, which the form
    /// waits on: leaving would cancel it, possibly after the server took it.
    @Published private(set) var isSaving = false

    /// True once the group is created, or what the user edited of it saved, which closes the form.
    @Published private(set) var isSaved = false

    /// The group created, set before `isSaved`, for the list to open it. Nil for a group being
    /// edited, whose form closes instead.
    @Published private(set) var createdGroupId: String?

    /// True once the group being edited is gone, deleted under the form by a creation the server
    /// refused, or the user is no longer in it, removed or left, which leaves it read-only: there is
    /// nothing left to edit, and the form closes.
    @Published private(set) var isGone = false

    /// Why the group could not be created, until it is told: the form keeps what was typed.
    @Published var creationError: GroupCreationError?

    /// Why what the user edited was not saved, until it is told: the form keeps what was typed, for
    /// them to save again.
    @Published var editError: GroupEditError?

    /// The names of the members of the group to create, as they are read.
    @Published private(set) var names = MemberNames()

    let mode: Mode
    let maxTitleLength: Int
    let maxDescriptionLength: Int
    let maxMembers: Int
    let currencies: [GroupCurrency]
    let selfUuid: String

    /// The picker the members of a group to create are picked with.
    let picker: MemberPicker

    private let callBacks: any GroupCallBacks
    private let groupTask = TaskHandle()
    private var nameFollower: NameFollower?

    init(callBacks: any GroupCallBacks, mode: Mode) {
        self.callBacks = callBacks
        self.mode = mode
        self.maxTitleLength = callBacks.maxTitleLength
        self.maxDescriptionLength = callBacks.maxDescriptionLength
        self.maxMembers = callBacks.maxMembers
        self.currencies = callBacks.currencies
        self.picker = MemberPicker(callBacks: callBacks, countsListedPicksOnly: false, requiresPick: false)

        let selfUuid = callBacks.authenticatedUserUuid() ?? ""

        self.selfUuid = selfUuid

        switch mode {
        case .create:
            isBeingRead = false
            // The user is in every group of theirs, whoever is picked.
            form = GroupForm(currency: callBacks.defaultCurrency, memberUuids: [selfUuid])

            nameFollower = NameFollower { [weak self] names in
                self?.names = names
            }

            followNames()

        case .edit(let groupId):
            isBeingRead = true
            form = GroupForm(currency: callBacks.defaultCurrency)

            // Read once into the form, for it to keep what the user types, and followed from then
            // on for what the group shows, which the form saves nothing but a change of, and for
            // the group going, or the user being out of it.
            follow(callBacks.group(id: groupId), owner: self, into: groupTask) { owner, group in
                owner.read(group)
            }
        }
    }

    var isNewGroup: Bool {
        mode == .create
    }

    /// Whether the form can be confirmed, see `GroupForm.canConfirm`.
    var canSave: Bool {
        !isSaving && !isSaved && !isBeingRead && !isGone
            && form.canConfirm(maxTitleLength: maxTitleLength, maxDescriptionLength: maxDescriptionLength)
    }

    /// Whether leaving the form would lose anything typed into it.
    var hasEdits: Bool {
        if let shown = form.shown {
            return form.trimmedTitle != shown.title || form.trimmedDescription != shown.description
        }

        return isNewGroup && (!form.trimmedTitle.isEmpty || !form.trimmedDescription.isEmpty || form.memberUuids.count > 1)
    }

    /// What the title field says is wrong with it, as Android's form says it: missing, once the user
    /// typed in it, or too long, counted as it is saved.
    var titleError: String? {
        let length = form.titleLength

        if length == 0 {
            return isTitleEdited ? String(localized: "A title is mandatory", bundle: .module) : nil
        }

        return length > maxTitleLength ? String(localized: "Title too long (\(length)/\(maxTitleLength))", bundle: .module) : nil
    }

    /// What the description field says is wrong with it: too long, counted as it is saved.
    var descriptionError: String? {
        let length = form.descriptionLength

        return length > maxDescriptionLength
            ? String(localized: "Description too long (\(length)/\(maxDescriptionLength))", bundle: .module)
            : nil
    }

    /// The members of the group to create as the form lists them: the user first, then the others
    /// by name.
    var displayedMembers: [String] {
        form.memberUuids.sortedByName(selfUuid: selfUuid, uuidOf: { $0 }, nameOf: { self.names.name(of: $0) })
    }

    func currencyDisplayName(_ currency: GroupCurrency) -> String {
        callBacks.currencyDisplayName(code: currency.code)
    }

    // MARK: - Fields

    func updateTitle(_ title: String) {
        form.title = title
        isTitleEdited = true
    }

    func updateDescription(_ description: String) {
        form.description = description
    }

    /// Picks `currency`, one of `currencies`, which the group keeps for good.
    func selectCurrency(_ currency: GroupCurrency) {
        form.currency = currency
    }

    // MARK: - Members

    /// Opens the picker on the members picked so far.
    func beginPickingMembers() {
        picker.open(picked: Set(form.memberUuids).subtracting([selfUuid]))
    }

    /// Lists the user and whoever the picker picked in the form, by name. The picker keeps showing
    /// them while its sheet slides away, see `endPickingMembers`.
    func confirmPickedMembers() {
        let selected = picker.selectedUuids
        let kept = keptMembers(memberUuids: form.memberUuids, selfUuid: selfUuid, selectedUuids: selected)

        form.memberUuids = kept + picker.orderedSelection.filter { !kept.contains($0) }
        followNames()
    }

    /// Once the picker's sheet is gone, confirmed or not: what it picked and searched goes with it,
    /// and the form keeps the members it was last confirmed with. Not before, for the sheet not to
    /// empty as it slides away.
    func endPickingMembers() {
        picker.close()
    }

    // MARK: - Saving

    func save() {
        // Not once saved either: each creation is a group of its own, and each edit the next one of
        // the group, made over the one just saved.
        guard canSave else {
            return
        }

        isSaving = true

        let saved = form
        // In the order the form lists them, the user aside, who is in it whatever.
        let memberUuids = displayedMembers.filter { $0 != selfUuid }

        Task {
            switch mode {
            case .create:
                let result = await callBacks.createGroup(
                    title: saved.trimmedTitle,
                    description: saved.trimmedDescription,
                    currency: saved.currency,
                    memberUuids: memberUuids
                )

                isSaving = false

                switch result {
                case .created(let groupId):
                    createdGroupId = groupId
                    isSaved = true
                case .failed(let error):
                    creationError = error
                }

            case .edit(let groupId):
                let error = await callBacks.editGroup(id: groupId, title: saved.trimmedTitle, description: saved.trimmedDescription)

                isSaving = false

                if let error {
                    editError = error
                } else {
                    isSaved = true
                }
            }
        }
    }

    // MARK: - Reading

    /// Takes `group`, the group being edited as the device now holds it: into the form the first
    /// time, and as what it shows since.
    private func read(_ group: GroupDetails?) {
        guard let group, !group.isLeft else {
            isGone = true
            groupTask.replace(with: nil)
            return
        }

        let shown = GroupInformation(title: group.title, description: group.description)

        if isBeingRead {
            form.title = group.title
            form.description = group.description
            form.currency = group.currency
            isBeingRead = false
        }

        form.shown = shown
    }

    private func followNames() {
        nameFollower?.follow(Set(form.memberUuids), callBacks: callBacks)
    }
}
