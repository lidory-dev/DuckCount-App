import Foundation

/// The new contact form's state, as Android's `NewContactViewModel` holds it.
@MainActor
final class NewContactViewModel: ObservableObject {
    /// What the user typed, as typed, emptied once a request is sent: rewriting what is being typed
    /// would move the cursor, and trimming it would swallow a space typed at the end. What is
    /// checked and sent is `trimmedUsername`.
    @Published private(set) var username = ""

    /// Why `trimmedUsername` cannot be anybody's, nil while nothing is typed.
    @Published private(set) var inputError: UsernameInputError?

    /// Whether a request is being sent, which the user can neither repeat nor leave.
    @Published private(set) var isSending = false

    /// Why the last request could not be sent, cleared as soon as the username changes.
    @Published private(set) var error: AddContactError?

    /// The username the last request went to, until the form confirmed it. A state rather than an
    /// event: the request can complete while the app is in the background.
    @Published private(set) var sentTo: String?

    let maxUsernameLength: Int

    private let callBacks: any ContactCallBacks

    init(callBacks: any ContactCallBacks) {
        self.callBacks = callBacks
        self.maxUsernameLength = callBacks.maxUsernameLength
    }

    var trimmedUsername: String {
        username.trimmedUsername
    }

    var canSend: Bool {
        !username.isBlank && inputError == nil && !isSending
    }

    func updateUsername(_ username: String) {
        self.username = username
        inputError = callBacks.validateUsername(trimmedUsername)
        error = nil
    }

    /// Sends a request to the username typed, from the button or the keyboard's Send alike.
    func send() {
        guard canSend else {
            return
        }

        let username = trimmedUsername

        isSending = true
        error = nil

        Task {
            let failure = await callBacks.sendRequest(username: username)

            if let failure {
                error = failure
            } else {
                // A sent request empties the field, ready for the next contact.
                self.username = ""
                inputError = nil
                sentTo = username
            }

            isSending = false
        }
    }

    /// The form confirmed the request to `username`: only that one is cleared, the next request
    /// could be sent before the confirmation of this one is over.
    func confirmSent(to username: String) {
        if sentTo == username {
            sentTo = nil
        }
    }
}
