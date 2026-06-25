import SwiftUI

/// "Add a new contact" sheet. The Add action is a placeholder until the backend
/// lookup exists.
public struct NewContactView: View {
    private let onCancel: (() -> Void)?

    @State private var username: String = ""
    @FocusState private var isUsernameFocused: Bool
    @Environment(\.dismiss) private var dismiss

    public init(onCancel: (() -> Void)? = nil) {
        self.onCancel = onCancel
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                TextField("Search by username", text: $username)
                    .textFieldStyle(.roundedBorder)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($isUsernameFocused)

                Button("Add") {
                    isUsernameFocused = false
                    // TODO: Search using the unique username to get user information.
                }
                .buttonStyle(.borderedProminent)
                .disabled(username.trimmingCharacters(in: .whitespaces).isEmpty)

                Spacer()
            }
            .padding()
            .navigationTitle("Add a new contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onCancel?()
                        dismiss()
                    }
                }
            }
        }
    }
}

#Preview {
    NewContactView()
}
