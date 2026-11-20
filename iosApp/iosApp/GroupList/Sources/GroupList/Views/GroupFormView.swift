import AppColors
import SwiftUI

/// The form a group is created with, its title, currency, description and members, or its title
/// and description edited with, by its owner, as Android's `CreateOrEditGroupScreen`. Shown in a
/// sheet of its own.
struct GroupFormView: View {
    private let onCreated: (String) -> Void

    @StateObject private var viewModel: GroupFormViewModel

    @State private var isConfirmingDiscard = false
    @State private var isPickingMembers = false

    @FocusState private var focusedField: Field?

    @Environment(\.dismiss) private var dismiss

    private enum Field: Hashable {
        case title
        case description
    }

    /// `onCreated` is handed the group created, before the form closes.
    init(callBacks: any GroupCallBacks, mode: GroupFormViewModel.Mode, onCreated: @escaping (String) -> Void = { _ in }) {
        self.onCreated = onCreated
        _viewModel = StateObject(wrappedValue: GroupFormViewModel(callBacks: callBacks, mode: mode))
    }

    var body: some View {
        NavigationStack {
            Form {
                titleSection

                if viewModel.isNewGroup {
                    currencySection
                }

                descriptionSection

                // A group's members are changed from its settings, by its creator alone.
                if viewModel.isNewGroup {
                    membersSection
                }
            }
            .appListBackground()
            .disabled(viewModel.isSaving || viewModel.isBeingRead)
            .navigationTitle(
                viewModel.isNewGroup ? Text("Create a group", bundle: .module) : Text("Edit group", bundle: .module)
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        if viewModel.hasEdits {
                            isConfirmingDiscard = true
                        } else {
                            dismiss()
                        }
                    } label: {
                        Text("Cancel", bundle: .module)
                    }
                    .disabled(viewModel.isSaving)
                    // On the button, for the dialog to point at it where it shows as a popover.
                    .confirmationDialog(
                        Text("Discard Changes", bundle: .module),
                        isPresented: $isConfirmingDiscard
                    ) {
                        Button(role: .destructive) {
                            dismiss()
                        } label: {
                            Text("Discard Changes", bundle: .module)
                        }

                        Button(role: .cancel) {} label: {
                            Text("Keep Editing", bundle: .module)
                        }
                    }
                }
            }
            .bottomBar {
                ConfirmButton(Text("Confirm", bundle: .module), isLoading: viewModel.isSaving) {
                    focusedField = nil
                    viewModel.save()
                }
                .disabled(!viewModel.canSave)
            }
            .overlay {
                if viewModel.isBeingRead {
                    ProgressView()
                }
            }
        }
        // Leaving would cancel the write, possibly after the server took it, or lose what was typed.
        .interactiveDismissDisabled(viewModel.isSaving || viewModel.hasEdits)
        .alert(
            viewModel.creationError?.message ?? viewModel.editError?.message ?? "",
            isPresented: Binding(
                get: { viewModel.creationError != nil || viewModel.editError != nil },
                set: { isPresented in
                    if !isPresented {
                        viewModel.creationError = nil
                        viewModel.editError = nil
                    }
                }
            )
        ) {
            Button(role: .cancel) {} label: {
                Text("OK", bundle: .module)
            }
        }
        // Closed, by its own buttons or swiped down, it drops what it picked but was not confirmed,
        // once it is gone.
        .sheet(isPresented: $isPickingMembers, onDismiss: viewModel.endPickingMembers) {
            MemberPickerView(
                picker: viewModel.picker,
                isConfirming: false,
                error: .constant(nil),
                onConfirm: {
                    viewModel.confirmPickedMembers()
                    isPickingMembers = false
                },
                onCancel: {
                    isPickingMembers = false
                }
            )
        }
        .onChange(of: viewModel.isSaved) { _, isSaved in
            if isSaved {
                if let groupId = viewModel.createdGroupId {
                    onCreated(groupId)
                }

                dismiss()
            }
        }
        // Gone already, the form opened from a screen still showing it.
        .onChange(of: viewModel.isGone, initial: true) { _, isGone in
            if isGone {
                dismiss()
            }
        }
        .onAppear {
            if viewModel.isNewGroup {
                focusedField = .title
            }
        }
    }

    private var titleSection: some View {
        Section {
            TextField(
                String(localized: "Title", bundle: .module),
                text: Binding(get: { viewModel.form.title }, set: viewModel.updateTitle)
            )
            .textInputAutocapitalization(.sentences)
            .submitLabel(.next)
            .focused($focusedField, equals: .title)
            .onSubmit { focusedField = .description }
        } footer: {
            // Counted as it is saved, without the whitespace around it, as Confirm counts it.
            if let error = viewModel.titleError {
                Text(error)
                    .foregroundStyle(AppColors.error)
            }
        }
        .appCardRows()
    }

    private var currencySection: some View {
        Section {
            Picker(
                selection: Binding(
                    get: { viewModel.form.currency },
                    set: viewModel.selectCurrency
                )
            ) {
                ForEach(viewModel.currencies) { currency in
                    Text(verbatim: "\(viewModel.currencyDisplayName(currency)) (\(currency.symbol))")
                        .tag(currency)
                }
            } label: {
                Text("Currency", bundle: .module)
            }
            .pickerStyle(.menu)
        }
        .appCardRows()
    }

    private var descriptionSection: some View {
        Section {
            TextField(
                String(localized: "Description", bundle: .module),
                text: Binding(get: { viewModel.form.description }, set: viewModel.updateDescription),
                axis: .vertical
            )
            .lineLimit(1...5)
            .focused($focusedField, equals: .description)
        } footer: {
            if let error = viewModel.descriptionError {
                Text(error)
                    .foregroundStyle(AppColors.error)
            }
        }
        .appCardRows()
    }

    private var membersSection: some View {
        Section {
            ForEach(viewModel.displayedMembers, id: \.self) { userUuid in
                MemberRow(
                    userUuid: userUuid,
                    name: viewModel.names.displayName(of: userUuid),
                    // The user, who creates it, and owns it for as long as it exists.
                    isOwner: userUuid == viewModel.selfUuid
                )
            }

            Button {
                focusedField = nil
                viewModel.beginPickingMembers()
                isPickingMembers = true
            } label: {
                if viewModel.form.memberUuids.count > 1 {
                    Label {
                        Text("Modify", bundle: .module)
                    } icon: {
                        Image(systemName: "pencil")
                    }
                } else {
                    Label {
                        Text("Add", bundle: .module)
                    } icon: {
                        Image(systemName: "person.badge.plus")
                    }
                }
            }
        } header: {
            Text("Members (\(viewModel.form.memberUuids.count)/\(viewModel.maxMembers))", bundle: .module)
        }
        .appCardRows()
    }
}

#Preview {
    GroupFormView(callBacks: PreviewGroupCallBacks(), mode: .create)
}
