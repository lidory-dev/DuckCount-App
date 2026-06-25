import SwiftUI

/// A single contact cell: colored initial avatar followed by the full name.
public struct ContactRowView: View {
    private let contact: Contact

    public init(contact: Contact) {
        self.contact = contact
    }

    public var body: some View {
        HStack(spacing: 12) {
            ContactAvatarView(contact: contact, size: 44)

            Text(contact.fullName)
                .font(.body)
                .lineLimit(1)

            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

#Preview {
    List {
        ContactRowView(contact: Contact(id: "1", fullName: "Alice Martin"))
        ContactRowView(contact: Contact(id: "2", fullName: "Bob Johnson"))
    }
    .listStyle(.plain)
}
