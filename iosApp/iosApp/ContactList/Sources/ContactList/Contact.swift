public struct Contact: Identifiable, Hashable {
    public let id: String
    public let username: String
    public let fullName: String
    public let profilePicture: String?

    public init(
        id: String,
        username: String = "",
        fullName: String = "",
        profilePicture: String? = nil
    ) {
        self.id = id
        self.username = username
        self.fullName = fullName
        self.profilePicture = profilePicture
    }
}
