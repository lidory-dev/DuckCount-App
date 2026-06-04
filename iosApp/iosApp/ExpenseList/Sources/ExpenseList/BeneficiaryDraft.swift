import Foundation

public struct BeneficiaryDraft: Identifiable, Hashable {
    public let id: String
    public let userUuid: String
    public let fullName: String
    public var share: String

    public init(
        id: String = UUID().uuidString,
        userUuid: String,
        fullName: String,
        share: String = ""
    ) {
        self.id = id
        self.userUuid = userUuid
        self.fullName = fullName
        self.share = share
    }
}
