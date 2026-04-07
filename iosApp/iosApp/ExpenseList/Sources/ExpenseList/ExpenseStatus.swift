import SwiftUI

public enum ExpenseStatus {
    case beneficiary
    case creditor
    case neutral

    public var symbol: String {
        switch self {
        case .beneficiary: "+"
        case .creditor: "-"
        case .neutral: ""
        }
    }

    public var color: Color {
        switch self {
        case .beneficiary: Color(red: 0x5c / 255.0, green: 0xcb / 255.0, blue: 0x5f / 255.0)
        case .creditor: Color(red: 0xe6 / 255.0, green: 0x39 / 255.0, blue: 0x46 / 255.0)
        case .neutral: Color(.lightGray)
        }
    }
}
