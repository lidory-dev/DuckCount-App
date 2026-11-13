import SwiftUI

/// A contact: their name, the safety number to check them by, and blocking or deleting them, as
/// Android's `ContactDetailScreen`.
struct ContactDetailView: View {
    private let callBacks: any ContactCallBacks
    private let onClose: () -> Void

    @StateObject private var viewModel: ContactDetailViewModel

    @State private var isConfirmingBlock = false
    @State private var isConfirmingDeletion = false

    init(contactId: String, callBacks: any ContactCallBacks, onClose: @escaping () -> Void) {
        self.callBacks = callBacks
        self.onClose = onClose
        _viewModel = StateObject(wrappedValue: ContactDetailViewModel(contactId: contactId, callBacks: callBacks))
    }

    var body: some View {
        let name = viewModel.contact?.displayName ?? unresolvedUserName

        List {
            Section {
                VStack(spacing: 12) {
                    ContactAvatar(userUuid: viewModel.contactId, name: name, size: 96)

                    Text(name)
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }

            if viewModel.isContact {
                Section {
                    NavigationLink {
                        ContactVerificationView(contactId: viewModel.contactId, callBacks: callBacks)
                    } label: {
                        HStack {
                            Label {
                                Text("Safety number", bundle: .module)
                            } icon: {
                                Image(systemName: "lock.shield")
                            }

                            Spacer()

                            if viewModel.verification?.isVerified == true {
                                VerifiedLabel()
                            }
                        }
                    }
                }

                Section {
                    Button(role: .destructive) {
                        isConfirmingBlock = true
                    } label: {
                        ProgressLabel(isInProgress: viewModel.isBlocking) {
                            Text("Block", bundle: .module)
                        } icon: {
                            // Red like its title: a row's icon keeps the app's tint, whatever the role.
                            Image(systemName: "nosign")
                                .foregroundStyle(.red)
                        }
                    }
                    .disabled(viewModel.isBusy)
                    // On the button, for the dialog to point at it where it shows as a popover.
                    .confirmationDialog(
                        Text("Block this contact?", bundle: .module),
                        isPresented: $isConfirmingBlock,
                        titleVisibility: .visible
                    ) {
                        Button(role: .destructive, action: viewModel.block) {
                            Text("Block", bundle: .module)
                        }
                    } message: {
                        Text(
                            "They will be removed from your contacts and won’t be able to send you contact requests or expenses any more. Groups you share stay as they are. They won’t be told you blocked them.",
                            bundle: .module
                        )
                    }
                }
            }

            Section {
                Button(role: .destructive) {
                    isConfirmingDeletion = true
                } label: {
                    ProgressLabel(isInProgress: viewModel.isRemoving) {
                        Text("Delete contact", bundle: .module)
                    } icon: {
                        Image(systemName: "trash")
                            .foregroundStyle(.red)
                    }
                }
                .disabled(viewModel.isBusy)
                .confirmationDialog(
                    Text("Delete contact", bundle: .module),
                    isPresented: $isConfirmingDeletion,
                    titleVisibility: .visible
                ) {
                    Button(role: .destructive, action: viewModel.remove) {
                        Text("Delete", bundle: .module)
                    }
                } message: {
                    Text("Are you sure you want to delete this contact?", bundle: .module)
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .alert(
            viewModel.error?.message ?? "",
            isPresented: Binding(get: { viewModel.error != nil }, set: { if !$0 { viewModel.error = nil } })
        ) {
            Button(role: .cancel) {} label: {
                Text("OK", bundle: .module)
            }
        }
        .onChange(of: viewModel.isRemoved) { _, isRemoved in
            if isRemoved {
                onClose()
            }
        }
    }
}

/// A label whose icon gives way to a spinner while what it started is under way.
struct ProgressLabel<Title: View, Icon: View>: View {
    let isInProgress: Bool
    @ViewBuilder let title: () -> Title
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        Label {
            title()
        } icon: {
            if isInProgress {
                ProgressView()
            } else {
                icon()
            }
        }
    }
}

/// Says that the user marked a contact's safety number verified.
struct VerifiedLabel: View {
    var body: some View {
        Label {
            Text("Verified", bundle: .module)
        } icon: {
            Image(systemName: "checkmark.seal.fill")
        }
        .foregroundStyle(.tint)
    }
}

#Preview {
    NavigationStack {
        ContactDetailView(contactId: "alice", callBacks: PreviewContactCallBacks(), onClose: {})
    }
}
