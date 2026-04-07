import SwiftUI

struct SectionHeaderView: View {
    let displayDate: String

    var body: some View {
        Text(displayDate)
            .font(.headline)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal)
            .padding(.vertical, 8)
            .background(Color(UIColor.systemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .shadow(color: .black.opacity(0.06), radius: 4, x: 0, y: 2)
    }
}
