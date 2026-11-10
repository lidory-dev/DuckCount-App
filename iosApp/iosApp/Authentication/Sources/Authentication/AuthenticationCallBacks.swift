import Foundation

/// What the authentication screens need from the rest of the app, which implements it on the
/// shared module, as Android's `AuthenticationCallBacks` is implemented in its app module: the
/// screens hold no business rule of their own, and this package knows nothing of `Shared`.
@MainActor
public protocol AuthenticationCallBacks: AnyObject {
    /// Shortest password a registration takes.
    var minimumPasswordLength: Int { get }

    /// Longest username the library accepts, for the "too long" message to count against.
    var maxUsernameLength: Int { get }

    /// Longest full name the server takes on a profile, in UTF-16 units, which its field stops at.
    var maxFullNameLength: Int { get }

    /// The terms of use creating an account accepts, which the registration form links to.
    var termsOfUseLink: URL { get }

    /// The privacy policy the registration form links to beside the terms of use.
    var privacyPolicyLink: URL { get }

    /// Why `username` cannot be used as typed, or nil when it is valid.
    func validateUsername(_ username: String) -> UsernameInputError?

    /// How many times `password` turns up in known breaches, or nil if that could not be found
    /// out, which lets the registration go ahead unchecked.
    func passwordLeakCount(password: String) async -> Int?

    /// Registers the user via OPAQUE, attesting the device first. Returns the new user's UUID.
    func register(username: String, password: String) async -> Result<String, AuthError>

    /// Authenticates the user via OPAQUE. Returns nil once logged in.
    func logIn(username: String, password: String) async -> AuthError?

    /// Remembers a registration whose profile step is still to complete, across launches.
    func markPendingRegistration(_ profile: PendingProfile)

    /// Stores the profile the user just filled in, on the server first, which finishes the
    /// registration. Returns nil once it has.
    func setUpProfile(_ profile: PendingProfile, fullName: String) async -> AuthError?
}
