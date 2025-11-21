import SwiftUI

struct ExpenseDetailView: View {
    let id: Int
    
    var body: some View {
        Text("Expense \(id)")
    }
}

#Preview {
    ExpenseDetailView(id: 1)
}
