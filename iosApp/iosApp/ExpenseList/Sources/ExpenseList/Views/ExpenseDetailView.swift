import SwiftUI

public struct ExpenseDetailView: View {
    let expenseUuid: String
    
    public init(expenseUuid: String) {
        self.expenseUuid = expenseUuid
    }
    
    public var body: some View {
        Text("Expense \(expenseUuid)")
    }
}

#Preview {
    ExpenseDetailView(expenseUuid: "1")
}
