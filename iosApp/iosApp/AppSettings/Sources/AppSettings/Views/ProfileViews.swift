import AppFonts
import SwiftUI

/// The user's full name, which only their contacts and the members of their groups see, as
/// Android's `UserProfileScreen`. It stays open once saved, the field then holding the name saved.
struct ProfileView: View {
    @StateObject private var viewModel: ProfileViewModel

    @FocusState private var isFieldFocused: Bool

    init(callBacks: any SettingsCallBacks) {
        _viewModel = StateObject(wrappedValue: ProfileViewModel(callBacks: callBacks))
    }

    var body: some View {
        Group {
            if viewModel.userUuid == nil {
                ContentUnavailableView {
                    Label {
                        Text("An error occurred", bundle: .module)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle")
                    }
                    .font(.app(.title2))
                }
            } else if viewModel.hasReadProfile {
                form
            } else {
                ProgressView()
            }
        }
        .navigationTitle(Text("Profile", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        // Leaving would cancel the save, possibly after the server took it.
        .navigationBarBackButtonHidden(viewModel.isSaving)
    }

    private var form: some View {
        Form {
            Section {
                Disclaimer(systemImage: "lock.shield") {
                    Text(
                        "Visible only to your contacts and group members.\n\nEverything is end-to-end encrypted, which means we can’t see your profile — only the people you choose to share it with.",
                        bundle: .module
                    )
                }
            }

            Section {
                TextField(
                    String(localized: "Full Name", bundle: .module),
                    text: Binding(get: { viewModel.fieldText }, set: viewModel.updateFullName)
                )
                .textContentType(.name)
                .textInputAutocapitalization(.words)
                // A name is no word of the dictionary, for the keyboard to correct.
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($isFieldFocused)
                .onSubmit(save)
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        if viewModel.isNameMissing {
                            FormMessage(String(localized: "Required", bundle: .module))
                        }

                        Spacer()

                        // Counted as the limit is, in UTF-16 units.
                        Text(verbatim: "\(viewModel.fieldText.limitLength)/\(viewModel.maxFullNameLength)")
                            .monospacedDigit()
                    }

                    if let error = viewModel.error {
                        FormMessage(error.message)
                    }
                }
            }
        }
        .disabled(viewModel.isSaving)
        .bottomBar {
            ConfirmButton(Text("Save", bundle: .module), isLoading: viewModel.isSaving, action: save)
                .disabled(!viewModel.canSave)
        }
    }

    private func save() {
        // Nothing to save yet: the keyboard stays up to finish typing.
        guard viewModel.canSave else {
            return
        }

        isFieldFocused = false
        viewModel.save()
    }
}

/// The form the discovery username is reserved, or changed, with, as Android's
/// `ReserveDiscoveryUsernameScreen`, which closes once it is: pushed from the settings, calling
/// `onReserved` then.
struct ReserveUsernameView: View {
    @StateObject private var viewModel: ReserveUsernameViewModel

    private let onReserved: () -> Void

    init(callBacks: any SettingsCallBacks, onReserved: @escaping () -> Void) {
        _viewModel = StateObject(wrappedValue: ReserveUsernameViewModel(callBacks: callBacks))
        self.onReserved = onReserved
    }

    var body: some View {
        ReserveUsernameForm(viewModel: viewModel, onReserved: onReserved)
            // Leaving would cancel the reservation, possibly after the server took it.
            .navigationBarBackButtonHidden(viewModel.isReserving)
    }
}

/// The username form in a sheet of its own, with a way to cancel it, which closes once the username
/// is reserved: where the contact list asks a user who has none yet to reserve one, as Android's
/// contact list opens its settings on the username screen.
public struct ReserveUsernameSheet: View {
    @StateObject private var viewModel: ReserveUsernameViewModel

    @Environment(\.dismiss) private var dismiss

    public init(callBacks: any SettingsCallBacks) {
        _viewModel = StateObject(wrappedValue: ReserveUsernameViewModel(callBacks: callBacks))
    }

    public var body: some View {
        NavigationStack {
            ReserveUsernameForm(viewModel: viewModel) {
                dismiss()
            }
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
    }
}

/// What the two ways to the username form share.
private struct ReserveUsernameForm: View {
    @ObservedObject var viewModel: ReserveUsernameViewModel
    let onReserved: () -> Void

    var body: some View {
        Group {
            if viewModel.hasReadReservedUsername {
                form
            } else {
                ProgressView()
            }
        }
        .navigationTitle(Text("Username", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: viewModel.isReserved) { _, isReserved in
            if isReserved {
                onReserved()
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
                // Done only closes the keyboard, as on Android: a new username releases the old one,
                // which only the button is deliberate enough for. No AutoFill content type: this is a
                // discovery username, never the login identifier `.username` would offer to fill in.
                TextField(
                    String(localized: "Username", bundle: .module),
                    text: Binding(get: { viewModel.fieldText }, set: viewModel.updateUsername)
                )
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.asciiCapable)
                .submitLabel(.done)
            } footer: {
                if let inputError = viewModel.inputError {
                    FormMessage(
                        inputError.message(length: viewModel.username.utf16.count, maxLength: viewModel.maxUsernameLength)
                    )
                } else if let error = viewModel.error {
                    FormMessage(error.message)
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

#Preview("Profile") {
    NavigationStack {
        ProfileView(callBacks: PreviewSettingsCallBacks())
    }
}

#Preview("Username") {
    ReserveUsernameSheet(callBacks: PreviewSettingsCallBacks())
}
