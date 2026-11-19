import Foundation

/// What the settings screens need from the rest of the app, which implements it on the shared
/// module, as Android's `SettingsCallBacks` is implemented in its app module: the screens hold no
/// business rule of their own, and this package knows nothing of `Shared`.
///
/// Each stream emits what the device holds as it changes, and stops being followed once the screen
/// reading it is gone.
@MainActor
public protocol SettingsCallBacks: AnyObject {
    /// Longest username the library accepts, for the "too long" message to count against.
    var maxUsernameLength: Int { get }

    /// Longest full name the server takes on a profile, which the profile's field stops at.
    var maxFullNameLength: Int { get }

    /// Shortest password the forms take.
    var minimumPasswordLength: Int { get }

    /// Lidory's site, which the About screen leads to.
    var learnMoreLink: URL { get }

    /// The app's source code, opened in Safari from the settings.
    var sourceCodeLink: URL { get }

    /// The terms of use, opened in Safari from the settings.
    var termsOfUseLink: URL { get }

    /// The privacy policy, opened in Safari from the settings.
    var privacyPolicyLink: URL { get }

    /// The version of the app the About screen says.
    var appVersion: String { get }

    /// The libraries the app ships, each with its licences, for the Licences screen.
    var libraries: [Library] { get }

    /// The user's own UUID, nil when nobody is signed in.
    func authenticatedUserUuid() -> String?

    /// The username the user logs in with.
    func authenticatedUsername() -> String?

    /// The full name on the profile of `userUuid`, as the device holds it, nil while it holds none.
    func fullName(of userUuid: String) -> AsyncStream<String?>

    /// Pulls the profile of `userUuid` from the server into the device's copy. A failure is not
    /// reported: the copy already held is what the screen shows either way.
    func refreshProfile(userUuid: String) async

    /// Saves the profile of `userUuid`, server first, with `fullName` taken without the whitespace
    /// around it. Returns nil once saved: nothing is kept on the device when the server did not take
    /// it.
    func updateProfile(userUuid: String, fullName: String) async -> ProfileSaveError?

    /// The username the user reserved to be discoverable by, nil while they have not reserved one.
    func reservedUsername() -> AsyncStream<String?>

    /// Brings the device's copy of the reserved username in line with the vault, which says which
    /// one the account holds.
    func refreshReservedUsername() async

    /// Why `username` cannot be reserved as typed, or nil when it is valid, or empty: nothing typed
    /// yet is no mistake to point out.
    func validateUsername(_ username: String) -> UsernameInputError?

    /// `username` as a reserved one is compared by.
    func normalizeUsername(_ username: String) -> String

    /// Reserves `username`, replacing whatever the user reserved before. Returns nil once reserved.
    func reserveUsername(_ username: String) async -> ReserveUsernameError?

    /// Changes the user's password from `currentPassword` to `newPassword`, once `newPassword` turns
    /// up in no known data breach, or the check could not be run. Returns nil once changed: the
    /// user's keys, their data and the session on this device stay as they are.
    func changePassword(currentPassword: String, newPassword: String) async -> PasswordChangeError?

    /// Ends the session on this device and starts the app over on its first screen, whatever becomes
    /// of the screen that asked.
    func logOut() async

    /// Deletes the user's account once `password` proves it is theirs, telling their contacts first,
    /// then starts the app over as `logOut` does, telling the user their account was deleted.
    /// Returns only when it failed, with why: the session and the account are still there then, as
    /// far as the attempt could tell, but for `AccountDeletionError.unconfirmed`.
    func deleteAccount(password: String) async -> AccountDeletionError
}
