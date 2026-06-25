import SwiftUI

/// Circular avatar showing the contact's first initial over a deterministic,
/// per-contact color.
public struct ContactAvatarView: View {
    private let contact: Contact
    private let size: CGFloat

    public init(contact: Contact, size: CGFloat) {
        self.contact = contact
        self.size = size
    }

    public var body: some View {
        // TODO: show the real profile picture once it is available.
        let colors = colorsForContact(uuid: contact.id)

        Circle()
            .fill(colors.container)
            .frame(width: size, height: size)
            .overlay {
                Text(initial)
                    .font(.system(size: size * 0.5, weight: .medium))
                    .foregroundStyle(colors.content)
            }
    }

    private var initial: String {
        contact.fullName.isEmpty ? "" : String(contact.fullName.prefix(1)).uppercased()
    }
}

#Preview {
    HStack(spacing: 16) {
        ContactAvatarView(contact: Contact(id: "1", fullName: "Alice Martin"), size: 44)
        ContactAvatarView(contact: Contact(id: "abc", fullName: "Bob Johnson"), size: 44)
        ContactAvatarView(contact: Contact(id: "xyz-9", fullName: "Charlie Davis"), size: 120)
    }
    .padding()
}
