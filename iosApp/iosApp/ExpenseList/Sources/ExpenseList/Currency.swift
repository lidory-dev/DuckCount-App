public struct Currency: Identifiable, Hashable {
    public let id: String
    public let name: String
    public let symbol: String
    public let hasSubunits: Bool

    public init(
        id: String,
        name: String = "",
        symbol: String = "",
        hasSubunits: Bool = false
    ) {
        self.id = id
        self.name = name
        self.symbol = symbol
        self.hasSubunits = hasSubunits
    }
}
