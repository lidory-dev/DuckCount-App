import SwiftUI

/// Everybody the user blocked, each with a way to unblock them, which leaves them no contact: a new
/// request, from either of the two, is what makes them one again. As Android's
/// `BlockedUserListScreen`.
struct BlockedUserListView: View {
    @StateObject private var viewModel: BlockedUserListViewModel

    init(callBacks: any ContactCallBacks) {
        _viewModel = StateObject(wrappedValue: BlockedUserListViewModel(callBacks: callBacks))
    }

    var body: some View {
        Group {
            if let users = viewModel.users {
                if users.isEmpty {
                    ContentUnavailableView {
                        Label {
                            Text("No blocked users", bundle: .module)
                        } icon: {
                            Image(systemName: "nosign")
                        }
                    }
                } else {
                    List {
                        Section {
                            ForEach(users) { user in
                                BlockedUserRow(user: user) {
                                    viewModel.unblock(user)
                                }
                            }
                        } footer: {
                            Text(
                                "Blocked users can’t send you contact requests or expenses. Unblocking someone doesn’t add them back to your contacts.",
                                bundle: .module
                            )
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(Text("Blocked users", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A blocked user, and the button that unblocks them once the user confirmed.
private struct BlockedUserRow: View {
    let user: BlockedUser
    let onUnblock: () -> Void

    @State private var isConfirming = false

    var body: some View {
        let name = user.fullName ?? unresolvedUserName

        HStack(spacing: 12) {
            ContactAvatar(userUuid: user.id, name: name)

            Text(name)
                .lineLimit(1)

            Spacer(minLength: 8)

            Button {
                isConfirming = true
            } label: {
                Text("Unblock", bundle: .module)
            }
            .buttonStyle(.bordered)
            // On the button, for the dialog to point at it where it shows as a popover.
            .confirmationDialog(
                Text("Unblock this user?", bundle: .module),
                isPresented: $isConfirming,
                titleVisibility: .visible
            ) {
                Button(action: onUnblock) {
                    Text("Unblock", bundle: .module)
                }
            } message: {
                Text("They will be able to send you contact requests again.", bundle: .module)
            }
        }
    }
}

/// The form a contact request is sent with, by the username the other person reserved, as
/// Android's `NewContactScreen`: a sent request empties the field, ready for the next one.
struct NewContactView: View {
    @StateObject private var viewModel: NewContactViewModel

    @FocusState private var isFieldFocused: Bool

    @Environment(\.dismiss) private var dismiss

    init(callBacks: any ContactCallBacks) {
        _viewModel = StateObject(wrappedValue: NewContactViewModel(callBacks: callBacks))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Disclaimer(systemImage: "person.badge.plus") {
                        Text(
                            "Enter the username of the person you want to add. They will receive a contact request, and once they accept it, you will be in each other’s contacts.\n\nOnly a fingerprint of the username reaches our servers, never the username itself.",
                            bundle: .module
                        )
                    }
                }

                Section {
                    UsernameField(
                        text: Binding(get: { viewModel.username }, set: viewModel.updateUsername),
                        submitLabel: .send,
                        onSubmit: send
                    )
                    .focused($isFieldFocused)
                } footer: {
                    if let inputError = viewModel.inputError {
                        FormMessage(
                            inputError.message(length: viewModel.trimmedUsername.utf16.count, maxLength: viewModel.maxUsernameLength),
                            isError: true
                        )
                    } else if let error = viewModel.error {
                        FormMessage(error.message, isError: true)
                    } else if let sentTo = viewModel.sentTo {
                        FormMessage(String(localized: "Contact request sent to \(sentTo)", bundle: .module), isError: false)
                    }
                }
            }
            .disabled(viewModel.isSending)
            .navigationTitle(Text("Add a new contact", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("Cancel", bundle: .module)
                    }
                    .disabled(viewModel.isSending)
                }
            }
            .bottomBar {
                ConfirmButton(Text("Send request", bundle: .module), isLoading: viewModel.isSending, action: send)
                    .disabled(!viewModel.canSend)
            }
        }
        // Leaving would cancel the request, possibly after the server took it.
        .interactiveDismissDisabled(viewModel.isSending)
        .onAppear {
            isFieldFocused = true
        }
        .onChange(of: viewModel.username) { _, _ in
            // Typing the next username is what dismisses the confirmation of the last request.
            if let sentTo = viewModel.sentTo, !viewModel.username.isEmpty {
                viewModel.confirmSent(to: sentTo)
            }
        }
        .onChange(of: viewModel.sentTo) { _, sentTo in
            // A footer changing is not read out, where Android's snackbar is.
            if let sentTo {
                AccessibilityNotification.Announcement(
                    String(localized: "Contact request sent to \(sentTo)", bundle: .module)
                ).post()
            }
        }
    }

    private func send() {
        // Nothing to send yet: the keyboard stays up to finish typing.
        guard viewModel.canSend else {
            return
        }

        viewModel.send()
    }
}

/// The form the discovery username is reserved, or changed, with, as Android's
/// `ReserveDiscoveryUsernameScreen`, which closes once it is.
struct ReserveUsernameView: View {
    @StateObject private var viewModel: ReserveUsernameViewModel

    @Environment(\.dismiss) private var dismiss

    init(callBacks: any ContactCallBacks) {
        _viewModel = StateObject(wrappedValue: ReserveUsernameViewModel(callBacks: callBacks))
    }

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.hasReadReservedUsername {
                    form
                } else {
                    ProgressView()
                }
            }
            .navigationTitle(Text("Username", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        dismiss()
                    } label: {
                        Text("Cancel", bundle: .module)
                    }
                    .disabled(viewModel.isReserving)
                }
            }
        }
        // Leaving would cancel the reservation, possibly after the server took it.
        .interactiveDismissDisabled(viewModel.isReserving)
        .onChange(of: viewModel.isReserved) { _, isReserved in
            if isReserved {
                dismiss()
            }
        }
    }

    private var form: some View {
        Form {
            Section {
                Disclaimer(systemImage: "at") {
                    if viewModel.hasReservedUsername {
                        Text(
                            "Choose the new username your contacts will use to add you.\n\nThe one you hold now is released as soon as the new one is reserved, so anybody still looking for the old one will no longer find you.",
                            bundle: .module
                        )
                    } else {
                        Text(
                            "Reserve the username your contacts will use to add you.\n\nOnly a fingerprint of it reaches our servers, so we never learn the username itself — only somebody who already knows it can find you.",
                            bundle: .module
                        )
                    }
                }
            }

            Section {
                // Done only closes the keyboard, as on Android: a new username releases the old
                // one, which only the button is deliberate enough for.
                UsernameField(
                    text: Binding(get: { viewModel.fieldText }, set: viewModel.updateUsername),
                    submitLabel: .done,
                    onSubmit: {}
                )
            } footer: {
                if let inputError = viewModel.inputError {
                    FormMessage(
                        inputError.message(length: viewModel.username.utf16.count, maxLength: viewModel.maxUsernameLength),
                        isError: true
                    )
                } else if let error = viewModel.error {
                    FormMessage(error.message, isError: true)
                }
            }
        }
        .disabled(viewModel.isReserving)
        .bottomBar {
            ConfirmButton(Text("Reserve", bundle: .module), isLoading: viewModel.isReserving, action: viewModel.reserve)
                .disabled(!viewModel.canReserve)
        }
    }
}

/// A username's field: letters and digits, no capitals nor corrections. No AutoFill content type:
/// these are discovery usernames, never the login identifier `.username` would offer to fill in.
private struct UsernameField: View {
    let text: Binding<String>
    let submitLabel: SubmitLabel
    let onSubmit: () -> Void

    var body: some View {
        TextField(String(localized: "Username", bundle: .module), text: text)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.asciiCapable)
            .submitLabel(submitLabel)
            .onSubmit(onSubmit)
    }
}

/// What a form says to begin with, under an icon.
private struct Disclaimer<Content: View>: View {
    let systemImage: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.largeTitle)
                .foregroundStyle(.tint)

            content()
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

/// What a form says under a field: why it cannot go on, in red, or what it just did.
private struct FormMessage: View {
    private let text: String
    private let isError: Bool

    init(_ text: String, isError: Bool) {
        self.text = text
        self.isError = isError
    }

    var body: some View {
        if isError {
            Text(text)
                .foregroundStyle(.red)
        } else {
            Label {
                Text(text)
            } icon: {
                Image(systemName: "checkmark.circle.fill")
            }
            .foregroundStyle(.green)
        }
    }
}

#Preview {
    NewContactView(callBacks: PreviewContactCallBacks())
}
