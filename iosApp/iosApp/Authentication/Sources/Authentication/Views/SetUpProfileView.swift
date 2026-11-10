import SwiftUI

/// The step that finishes a registration: the full name the user's contacts and group members see.
/// There is no going back from it, the account existing already, and a launch with it still to do
/// opens on it, see `PendingProfile`.
struct SetUpProfileView: View {
    @StateObject private var viewModel: SetUpProfileViewModel

    private let onSaved: @MainActor () -> Void

    @FocusState private var isFullNameFocused: Bool

    init(profile: PendingProfile, callBacks: any AuthenticationCallBacks, onSaved: @escaping @MainActor () -> Void) {
        _viewModel = StateObject(wrappedValue: SetUpProfileViewModel(profile: profile, callBacks: callBacks))
        self.onSaved = onSaved
    }

    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 72))
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)

                    Text(
                        "Visible to your contacts and group members. Everything is end-to-end encrypted, which means we can’t see your profile — only the people you choose to share it with.",
                        bundle: .module
                    )
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }

            Section {
                TextField(
                    String(localized: "Full Name", bundle: .module),
                    text: Binding(get: { viewModel.fullName }, set: viewModel.updateFullName)
                )
                .textContentType(.name)
                .textInputAutocapitalization(.words)
                // A name is no word of the dictionary, for the keyboard to correct.
                .autocorrectionDisabled()
                .submitLabel(.done)
                .focused($isFullNameFocused)
                .onSubmit(save)
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Spacer()

                        // Counted as the limit is, in UTF-16 units.
                        Text(verbatim: "\(viewModel.fullName.utf16.count)/\(viewModel.maxFullNameLength)")
                            .monospacedDigit()
                    }

                    FormErrors(viewModel.error?.message)
                }
            }
        }
        .disabled(viewModel.isSaving)
        .navigationTitle(Text("Set up your profile", bundle: .module))
        .navigationBarBackButtonHidden()
        .bottomBar {
            ConfirmButton("Done", isLoading: viewModel.isSaving, action: save)
                .disabled(!viewModel.canSave)
        }
        .onAppear { isFullNameFocused = true }
    }

    private func save() {
        isFullNameFocused = false
        viewModel.save(onSaved: onSaved)
    }
}

#Preview {
    NavigationStack {
        SetUpProfileView(
            profile: PendingProfile(uuid: "", username: "alice", hasPasswordBeenChecked: true),
            callBacks: PreviewAuthenticationCallBacks(),
            onSaved: {}
        )
    }
}
