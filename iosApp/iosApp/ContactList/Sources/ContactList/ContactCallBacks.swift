import Foundation

/// What the contact screens need from the rest of the app, which implements it on the shared
/// module, as Android's `ContactCallBacks`, and the part of its `SettingsCallBacks` that reserves
/// the discovery username, are implemented in its app module: the screens hold no business rule of
/// their own, and this package knows nothing of `Shared`.
///
/// Each stream emits what the device shows as it changes, and stops being followed once the screen
/// reading it is gone.
@MainActor
public protocol ContactCallBacks: AnyObject {
    /// Longest username the library accepts, for the "too long" message to count against.
    var maxUsernameLength: Int { get }

    /// The user's own UUID, whom the contact list leaves out.
    func authenticatedUserUuid() -> String?

    /// The people the device holds a name for whose name contains `filter`, the user among them.
    func contacts(matching filter: String) -> AsyncStream<[Contact]>

    /// The person `id`, as the device holds them, once it holds them.
    func contact(id: String) -> AsyncStream<Contact>

    /// The username the user reserved to be discoverable by, nil while they have not reserved one.
    func reservedUsername() -> AsyncStream<String?>

    /// How many contact requests are waiting for the user.
    func contactRequestCount() -> AsyncStream<Int>

    /// The contact requests waiting for the user, oldest first.
    func contactRequests() -> AsyncStream<[ContactRequest]>

    /// Everybody the user blocked.
    func blockedUsers() -> AsyncStream<[BlockedUser]>

    /// Where the user stands with checking the contact `contactId`.
    func verification(of contactId: String) -> AsyncStream<ContactVerification>

    /// Brings the contacts and the contact requests up to date. A failure is not reported: the user
    /// keeps seeing what they already had, and the next refresh picks up whatever did not get done.
    func refresh() async

    /// Removes the contact `id`, here and from the vault, once they are told, so that they remove
    /// the user in turn. Returns nil once removed: nothing is removed until they have been told.
    func removeContact(id: String) async -> RemoveContactError?

    /// Blocks the contact `id`: removes them as a removal does, telling them as for any removal,
    /// never that they were blocked, then refuses anything coming from them.
    func blockContact(id: String) async -> RemoveContactError?

    /// Lifts the block on `userUuid`, who is no contact of the user's for it.
    func unblock(userUuid: String) async

    /// Marks `safetyNumber`, the one the user compared with the contact `contactId`, verified.
    func markVerified(contactId: String, safetyNumber: String) async

    /// Clears the mark `markVerified` made for the contact `contactId`.
    func clearVerification(contactId: String) async

    /// Accepts the contact request `id`, making its sender a contact. Returns nil once accepted.
    func acceptRequest(id: String) async -> AnswerContactRequestError?

    /// Refuses the contact request `id`, which its sender is not told. Returns nil once refused.
    func refuseRequest(id: String) async -> AnswerContactRequestError?

    /// Blocks the sender of the contact request `id` without accepting it, refusing it first.
    func blockRequestSender(id: String) async -> AnswerContactRequestError?

    /// Why `username` cannot be looked up or reserved as typed, or nil when it is valid, or empty:
    /// nothing typed yet is no mistake to point out.
    func validateUsername(_ username: String) -> UsernameInputError?

    /// `username` as a reserved one is compared by.
    func normalizeUsername(_ username: String) -> String

    /// Sends a contact request to whoever reserved the discovery `username`. Returns nil once sent.
    func sendRequest(username: String) async -> AddContactError?

    /// Brings the device's copy of the reserved username in line with the vault, which says which
    /// one the account holds.
    func refreshReservedUsername() async

    /// Reserves `username`, replacing whatever the user reserved before. Returns nil once reserved.
    func reserveUsername(_ username: String) async -> ReserveUsernameError?
}
