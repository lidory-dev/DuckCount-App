import Foundation

/// The settings list's state: who the user is, for its header, and the username they reserved, for
/// its row.
@MainActor
final class SettingsViewModel: ObservableObject {
    /// The name on the user's profile, nil until read and while there is none.
    @Published private(set) var fullName: String?

    /// The username the user reserved to be discoverable by, nil while there is none.
    @Published private(set) var reservedUsername: String?

    let userUuid: String?

    /// The username the user logs in with, named when the profile holds no name.
    let username: String?

    private let fullNameTask = TaskHandle()
    private let reservedTask = TaskHandle()

    init(callBacks: any SettingsCallBacks) {
        userUuid = callBacks.authenticatedUserUuid()
        username = callBacks.authenticatedUsername()

        if let userUuid {
            follow(callBacks.fullName(of: userUuid), owner: self, into: fullNameTask) { owner, fullName in
                owner.fullName = fullName.flatMap { $0.isBlank ? nil : $0 }
            }
        }

        follow(callBacks.reservedUsername(), owner: self, into: reservedTask) { owner, username in
            owner.reservedUsername = username
        }
    }

    /// The name the header shows: the profile's, or else the username the user logs in with.
    var displayName: String {
        fullName ?? username ?? ""
    }
}

/// The profile screen's state, as Android's `UserProfileViewModel` holds it: the full name, which
/// the server holds and the device keeps a copy of.
@MainActor
final class ProfileViewModel: ObservableObject {
    /// The name the profile holds, as the device has it, empty while it holds none.
    @Published private(set) var savedFullName = ""

    /// Whether the device's copy was read, which the screen waits for.
    @Published private(set) var hasReadProfile = false

    /// What the user typed, as typed, nil while the field still holds the saved name.
    @Published private(set) var draft: String?

    @Published private(set) var isSaving = false

    /// Why the last save failed, cleared as soon as the name changes.
    @Published private(set) var error: ProfileSaveError?

    /// The user whose profile this is, nil when nobody is signed in, which leaves nothing to show.
    let userUuid: String?

    /// The longest full name the form takes, in UTF-16 units, which its field stops at.
    let maxFullNameLength: Int

    private let callBacks: any SettingsCallBacks
    private let fullNameTask = TaskHandle()

    init(callBacks: any SettingsCallBacks) {
        self.callBacks = callBacks
        self.userUuid = callBacks.authenticatedUserUuid()
        self.maxFullNameLength = callBacks.maxFullNameLength

        guard let userUuid else {
            return
        }

        follow(callBacks.fullName(of: userUuid), owner: self, into: fullNameTask) { owner, fullName in
            owner.savedFullName = fullName ?? ""
            owner.hasReadProfile = true
        }

        // The server holds the profile: reading it again on every entry is what shows a change made
        // on another device. The copy shows at once and is replaced when the read lands.
        Task {
            await callBacks.refreshProfile(userUuid: userUuid)
        }
    }

    /// The text the field shows: as typed once edited, the saved name until then.
    var fieldText: String {
        draft ?? savedFullName
    }

    /// The name as it is checked and saved, without the whitespace around it: a space added to the
    /// saved name alone is nothing to save. The field holds it as typed, for a space typed after a
    /// word not to be swallowed.
    var trimmedFullName: String {
        fieldText.trimmed
    }

    /// Whether the field holds no name, which it says.
    var isNameMissing: Bool {
        fieldText.isBlank
    }

    var canSave: Bool {
        !trimmedFullName.isEmpty && trimmedFullName != savedFullName && !isSaving
    }

    /// Takes `fullName` as typed, up to `maxFullNameLength`, past which the field takes nothing.
    func updateFullName(_ fullName: String) {
        var name = fullName

        // Character by character, so that no character is cut in half.
        while name.limitLength > maxFullNameLength {
            name.removeLast()
        }

        // Even when it is what the field held already: the field only drops what was typed past the
        // limit once it is told its text again.
        draft = name
        error = nil
    }

    func save() {
        guard let userUuid, canSave else {
            return
        }

        let fullName = trimmedFullName

        isSaving = true
        error = nil

        Task {
            let failure = await callBacks.updateProfile(userUuid: userUuid, fullName: fullName)

            isSaving = false
            error = failure

            // Only a save the server took is worth dropping the draft for: otherwise the user keeps
            // what they typed and can try again. The saved name the device's copy now holds is what
            // the field shows from here on.
            if failure == nil {
                savedFullName = fullName
                draft = nil
            }
        }
    }
}

/// The username screen's state, reserving a first one or changing it, as Android's
/// `ReserveDiscoveryUsernameViewModel` holds it: both are the same call, the server replacing
/// whatever the user had reserved before, so only the wording differs.
@MainActor
final class ReserveUsernameViewModel: ObservableObject {
    /// The username reserved, as the device holds it.
    @Published private(set) var reservedUsername: String?

    /// Whether the reserved username was read, which the screen waits for.
    @Published private(set) var hasReadReservedUsername = false

    /// What the user typed, as typed, nil while the field still holds the reserved username.
    @Published private(set) var draft: String?

    @Published private(set) var isReserving = false

    /// Why the last reservation failed, cleared as soon as the username changes.
    @Published private(set) var error: ReserveUsernameError?

    /// True once the username is reserved, the only thing the screen is for, which closes it.
    @Published private(set) var isReserved = false

    let maxUsernameLength: Int

    private let callBacks: any SettingsCallBacks
    private let reservedTask = TaskHandle()

    init(callBacks: any SettingsCallBacks) {
        self.callBacks = callBacks
        self.maxUsernameLength = callBacks.maxUsernameLength

        follow(callBacks.reservedUsername(), owner: self, into: reservedTask) { owner, username in
            owner.reservedUsername = username
            owner.hasReadReservedUsername = true
        }

        // The vault is what says which handle the account holds, so the copy shown is refreshed
        // from it on every entry: it shows at once and is replaced when the read lands.
        Task {
            await callBacks.refreshReservedUsername()
        }
    }

    /// The text the field shows: as typed once edited, the reserved username until then.
    var fieldText: String {
        draft ?? reservedUsername ?? ""
    }

    /// The username as it is checked and reserved.
    var username: String {
        draft?.trimmedUsername ?? reservedUsername ?? ""
    }

    var hasReservedUsername: Bool {
        reservedUsername != nil
    }

    var inputError: UsernameInputError? {
        callBacks.validateUsername(username)
    }

    /// Whether the username is the one reserved already, which there is no point reserving again.
    var isUnchanged: Bool {
        guard let reservedUsername else {
            return false
        }

        return reservedUsername == callBacks.normalizeUsername(username)
    }

    var canReserve: Bool {
        !username.isBlank && inputError == nil && !isReserving && !isUnchanged && !isReserved
    }

    func updateUsername(_ username: String) {
        draft = username
        error = nil
    }

    func reserve() {
        guard canReserve else {
            return
        }

        let username = username

        isReserving = true
        error = nil

        Task {
            let failure = await callBacks.reserveUsername(username)

            if failure == nil {
                isReserved = true
            }

            isReserving = false
            error = failure
        }
    }
}
