import SwiftUI

/// Contact detail: a large avatar above the full name.
public struct ContactDetailView: View {
    private let contact: Contact

    public init(contact: Contact) {
        self.contact = contact
    }

    public var body: some View {
        VStack(spacing: 16) {
            ContactAvatarView(contact: contact, size: 120)

            Text(contact.fullName)
                .font(.title2)
                .multilineTextAlignment(.center)

            if !contact.username.isEmpty {
                Text("@\(contact.username)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 32)
        .padding(.horizontal)
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack {
        ContactDetailView(
            contact: Contact(id: "1", username: "alice", fullName: "Alice Martin")
        )
    }
}
