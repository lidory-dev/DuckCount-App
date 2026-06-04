public struct User: Identifiable, Hashable {
    public let id: String
    public let username: String
    public let fullName: String

    public init(id: String, username: String = "", fullName: String = "") {
        self.id = id
        self.username = username
        self.fullName = fullName
    }
}
