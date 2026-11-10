import Foundation

/// The state of the profile step that finishes a registration, as Android's
/// `UserInformationViewModel` holds it.
@MainActor
public final class SetUpProfileViewModel: ObservableObject {
    @Published public private(set) var fullName = ""
    @Published public private(set) var isSaving = false
    @Published public private(set) var error: AuthError?

    let profile: PendingProfile

    private let callBacks: any AuthenticationCallBacks

    /// Whether the server holds the profile, which finishes the registration.
    private var isSaved = false

    public init(profile: PendingProfile, callBacks: any AuthenticationCallBacks) {
        self.profile = profile
        self.callBacks = callBacks
    }

    /// The longest full name the form takes, in UTF-16 units, which its field stops at.
    var maxFullNameLength: Int {
        callBacks.maxFullNameLength
    }

    var canSave: Bool {
        !fullName.isBlank && !isSaving
    }

    /// Takes `fullName` as typed, up to `maxFullNameLength`, past which the field takes nothing.
    func updateFullName(_ fullName: String) {
        var name = fullName

        // Character by character, so that no character is cut in half.
        while name.utf16.count > maxFullNameLength {
            name.removeLast()
        }

        // Even when it is what the field held already: the field only drops what was typed past
        // the limit once it is told its text again.
        self.fullName = name
        error = nil
    }

    /// Saves the profile, calling `onSaved` once the server holds it.
    func save(onSaved: @escaping @MainActor () -> Void) {
        guard canSave, !isSaved else {
            return
        }

        isSaving = true
        error = nil

        Task {
            let failure = await callBacks.setUpProfile(profile, fullName: fullName)

            isSaving = false

            if let failure {
                error = failure
            } else {
                // The profile is only saved once the server holds it, so this is the only outcome
                // that finishes the registration.
                isSaved = true
                onSaved()
            }
        }
    }
}
