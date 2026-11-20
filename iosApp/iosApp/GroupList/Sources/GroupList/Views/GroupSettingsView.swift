import AppColors
import AppFonts
import SwiftUI

/// Where the sheet of a member leads, once it is gone: a screen cannot be pushed from under a sheet
/// still on its way out.
private enum MemberSheetOutcome {
    case contact(userUuid: String)
    case contactRequests
    case removal(userUuid: String)
}

/// A group's settings: its title and description, its members, each opening a sheet of their own,
/// adding them, and leaving or deleting the group, as Android's `GroupSettingsScreen`. Its history
/// and its edit are in the toolbar.
struct GroupSettingsView: View {
    private let callBacks: any GroupCallBacks
    private let destinations: GroupDestinations

    @StateObject private var viewModel: GroupSettingsViewModel

    @Environment(\.isGroupChangeUnderWay) private var isGroupChangeUnderWay

    @State private var isShowingHistory = false
    @State private var isEditing = false
    @State private var isConfirmingLeave = false
    @State private var isConfirmingDelete = false
    @State private var isConfirmingForget = false
    @State private var memberToRemove: String?
    @State private var shownContact: String?
    @State private var isShowingContactRequests = false
    @State private var sheetOutcome: MemberSheetOutcome?

    init(groupId: String, callBacks: any GroupCallBacks, destinations: GroupDestinations) {
        self.callBacks = callBacks
        self.destinations = destinations
        _viewModel = StateObject(wrappedValue: GroupSettingsViewModel(groupId: groupId, callBacks: callBacks))
    }

    var body: some View {
        List {
            if let group = viewModel.group {
                header(group)

                if group.isLeft {
                    Section {
                        NoticeCard(GroupNotices.notice(isDeleted: group.isDeleted))
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                    }
                    .appCardRows()
                }

                membersSection

                actionsSection(group)
            }
        }
        .appListBackground()
        .overlay {
            if viewModel.group == nil {
                GroupSettingsSkeleton()
            }
        }
        // Where Android's top bar has none: the group's title heads the list, and this is what the
        // screens pushed from here lead back to.
        .navigationTitle(Text("Group settings", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbar }
        // While a change is on its way, nothing leads off the screen, as Android's confirmation stays
        // up until it is answered: left meanwhile, the screen would never tell a failure, and a
        // change made from elsewhere would race it. Hiding Back also stops the swipe back.
        .navigationBarBackButtonHidden(viewModel.isChanging)
        .onChange(of: viewModel.isChanging) { _, isChanging in
            isGroupChangeUnderWay.wrappedValue = isChanging
        }
        // Back from another tab of the app, which it can be left for meanwhile.
        .onAppear {
            isGroupChangeUnderWay.wrappedValue = viewModel.isChanging
        }
        // Gone with its group, deleted, or for another tab of the app.
        .onDisappear {
            isGroupChangeUnderWay.wrappedValue = false
        }
        .navigationDestination(isPresented: $isShowingHistory) {
            GroupHistoryView(groupId: viewModel.groupId, callBacks: callBacks)
        }
        .navigationDestination(item: $shownContact) { userUuid in
            destinations.contactDetail(userUuid)
        }
        .navigationDestination(isPresented: $isShowingContactRequests) {
            destinations.contactRequests()
        }
        .sheet(isPresented: $isEditing) {
            GroupFormView(callBacks: callBacks, mode: .edit(groupId: viewModel.groupId))
        }
        .sheet(
            isPresented: Binding(
                get: { viewModel.isPickingMembers },
                set: { if !$0 { viewModel.cancelAddingMembers() } }
            ),
            onDismiss: viewModel.endAddingMembers
        ) {
            MemberPickerView(
                picker: viewModel.picker,
                isConfirming: viewModel.isChanging,
                error: $viewModel.additionError,
                onConfirm: viewModel.addMembers,
                onCancel: viewModel.cancelAddingMembers
            )
        }
        .sheet(
            item: Binding(
                get: { viewModel.openedMemberUuid.map(OpenedMember.init) },
                set: { if $0 == nil { viewModel.closeMember() } }
            ),
            onDismiss: followSheetOutcome
        ) { opened in
            MemberSheetView(userUuid: opened.id, viewModel: viewModel) { outcome in
                sheetOutcome = outcome
                viewModel.closeMember()
            }
        }
        .alert(
            Text("Remove from the group", bundle: .module),
            isPresented: Binding(get: { memberToRemove != nil }, set: { if !$0 { memberToRemove = nil } }),
            presenting: memberToRemove
        ) { userUuid in
            Button(role: .destructive) {
                viewModel.remove(userUuid: userUuid)
            } label: {
                Text("Remove", bundle: .module)
            }

            Button(role: .cancel) {} label: {
                Text("Cancel", bundle: .module)
            }
        } message: { userUuid in
            Text("Remove \(viewModel.names.displayName(of: userUuid)) from “\(viewModel.group?.title ?? "")”?", bundle: .module)
        }
        .alert(
            viewModel.notice?.message ?? "",
            isPresented: Binding(get: { viewModel.notice != nil }, set: { if !$0 { viewModel.notice = nil } })
        ) {
            Button(role: .cancel) {} label: {
                Text("OK", bundle: .module)
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        if let group = viewModel.group {
            // To every member still in it, whom alone the server gives it to.
            if group.isHistoryReadable {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isShowingHistory = true
                    } label: {
                        Label {
                            Text("Group history", bundle: .module)
                        } icon: {
                            Image(systemName: "clock.arrow.circlepath")
                        }
                    }
                    .disabled(viewModel.isChanging)
                }
            }

            // To its owner alone, whose versions of it are the ones its members show. Not while a
            // change is on its way: an edit would be sent alongside it, a deletion say.
            if group.canEdit {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        isEditing = true
                    } label: {
                        Text("Edit", bundle: .module)
                    }
                    .disabled(viewModel.isChanging)
                }
            }
        }
    }

    private func header(_ group: GroupDetails) -> some View {
        Section {
            VStack(spacing: 8) {
                Text(group.title)
                    .font(.app(.title))

                if !group.description.isBlank {
                    Text(group.description)
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .listRowBackground(Color.clear)
        }
        .appCardRows()
    }

    private var membersSection: some View {
        let actions = viewModel.memberActions

        return Section {
            if actions != .none {
                Button {
                    viewModel.beginAddingMembers()
                } label: {
                    Label {
                        Text("Add members", bundle: .module)
                    } icon: {
                        Image(systemName: "person.badge.plus")
                    }
                }
                .disabled(actions != .available || viewModel.isChanging)
            }

            ForEach(viewModel.members) { member in
                memberRow(member)
            }
        } header: {
            Text("\(viewModel.members.count) members", bundle: .module)
        } footer: {
            if actions == .waiting {
                Text("Members can be changed once the group’s last change reaches the server", bundle: .module)
            }
        }
    }

    @ViewBuilder
    private func memberRow(_ member: GroupMember) -> some View {
        let row = HStack {
            MemberRow(userUuid: member.id, name: viewModel.names.displayName(of: member.id), isOwner: member.isOwner)

            if viewModel.removingUuid == member.id {
                ProgressView()
            }
        }

        // The user is shown alone: their sheet would offer nothing.
        if member.id == viewModel.selfUuid {
            row
        } else {
            Button {
                viewModel.openMember(userUuid: member.id)
            } label: {
                row
            }
            // One change at a time, which the sheet could make another of.
            .disabled(viewModel.isChanging)
        }
    }

    /// What the user does about the group as a whole, last, in red: the one way out of it, which is
    /// its owner's deletion, or, once they are out, taking its copy off this device.
    private func actionsSection(_ group: GroupDetails) -> some View {
        Section {
            if group.isLeavable {
                Button(role: .destructive) {
                    isConfirmingLeave = true
                } label: {
                    ProgressLabel(isInProgress: viewModel.change == .leave) {
                        Text("Leave the group", bundle: .module)
                    } icon: {
                        Image(systemName: "rectangle.portrait.and.arrow.right")
                    }
                    // Its title as well as its icon: the role would leave the one in iOS's red, the other in the tint.
                    .foregroundStyle(AppColors.error)
                }
                .disabled(viewModel.isChanging)
                // On the button, for the dialog to point at it where it shows as a popover.
                .confirmationDialog(
                    Text("Leave “\(group.title)”?", bundle: .module),
                    isPresented: $isConfirmingLeave,
                    titleVisibility: .visible
                ) {
                    Button(role: .destructive, action: viewModel.leave) {
                        Text("Leave", bundle: .module)
                    }
                } message: {
                    Text(
                        "The group stays on this device, read-only. Only the person who created it can add you back.",
                        bundle: .module
                    )
                }
            }

            // Not while a change waits on the server, as for its members.
            if group.canDelete {
                Button(role: .destructive) {
                    isConfirmingDelete = true
                } label: {
                    ProgressLabel(isInProgress: viewModel.change == .delete) {
                        Text("Delete the group", bundle: .module)
                    } icon: {
                        Image(systemName: "trash")
                    }
                    // Its title as well as its icon: the role would leave the one in iOS's red, the other in the tint.
                    .foregroundStyle(AppColors.error)
                }
                .disabled(viewModel.isChanging || group.hasPendingChange)
                .confirmationDialog(
                    Text("Delete “\(group.title)” for everyone?", bundle: .module),
                    isPresented: $isConfirmingDelete,
                    titleVisibility: .visible
                ) {
                    Button(role: .destructive, action: viewModel.delete) {
                        Text("Delete", bundle: .module)
                    }
                } message: {
                    Text(
                        "Its expenses and balances are erased from the server, even if some are unsettled, and are no longer tracked. Members keep a read-only copy on their phones, but it’s gone from yours. This can’t be undone.",
                        bundle: .module
                    )
                }
            }

            // All that is left to do about a group the user is out of: the copy this device keeps.
            if group.isRemovableFromDevice {
                Button(role: .destructive) {
                    isConfirmingForget = true
                } label: {
                    ProgressLabel(isInProgress: viewModel.change == .forget) {
                        Text("Delete from this device", bundle: .module)
                    } icon: {
                        Image(systemName: "trash")
                    }
                    // Its title as well as its icon: the role would leave the one in iOS's red, the other in the tint.
                    .foregroundStyle(AppColors.error)
                }
                .disabled(viewModel.isChanging)
                .confirmationDialog(
                    Text("Delete “\(group.title)” from this device?", bundle: .module),
                    isPresented: $isConfirmingForget,
                    titleVisibility: .visible
                ) {
                    Button(role: .destructive, action: viewModel.forget) {
                        Text("Delete", bundle: .module)
                    }
                } message: {
                    Text(
                        "Its expenses and balances will no longer show on this phone. Nothing changes for the other members. This can’t be undone.",
                        bundle: .module
                    )
                }
            }
        }
        .appCardRows()
    }

    /// Goes where the member's sheet led, once it is gone.
    private func followSheetOutcome() {
        guard let outcome = sheetOutcome else {
            return
        }

        sheetOutcome = nil

        switch outcome {
        case .contact(let userUuid):
            shownContact = userUuid
        case .contactRequests:
            isShowingContactRequests = true
        case .removal(let userUuid):
            memberToRemove = userUuid
        }
    }
}

/// The member whose sheet is open, by their UUID.
private struct OpenedMember: Identifiable {
    let id: String
}

/// A member of the group other than the user: who they are, what where the user stands with them
/// calls for, and their removal for the group's owner, as Android's `GroupMemberBottomSheet`.
private struct MemberSheetView: View {
    let userUuid: String

    @ObservedObject var viewModel: GroupSettingsViewModel

    /// Closes the sheet for what it leads to.
    let onOutcome: (MemberSheetOutcome) -> Void

    var body: some View {
        let name = viewModel.names.displayName(of: userUuid)

        ScrollView {
            VStack(spacing: 16) {
                MemberAvatar(userUuid: userUuid, name: name, size: 96)

                Text(name)
                    .font(.app(.title2))
                    .multilineTextAlignment(.center)

                if let actions = viewModel.memberSheetActions {
                    VStack(spacing: 12) {
                        ForEach(actions, id: \.self) { action in
                            button(for: action)

                            // Under the button it is about.
                            if action != .remove, let error = viewModel.requestError {
                                Text(error.message)
                                    .font(.app(.subheadline))
                                    .foregroundStyle(AppColors.error)
                                    .multilineTextAlignment(.center)
                            }
                        }
                    }
                    .padding(.top, 8)
                } else {
                    // Its one button, which most sheets have, until where the user stands with the
                    // member is read.
                    ButtonBone()
                        .padding(.top, 8)
                        .skeleton(.loading)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .presentationDetents([.medium, .large])
        // Android's bottom sheet's colour.
        .presentationBackground(AppColors.surfaceContainerLow)
        .presentationDragIndicator(.visible)
        // Closed while a request is on its way, it would cut short what comes of it.
        .interactiveDismissDisabled(viewModel.isSendingRequest)
        .animation(.default, value: viewModel.requestError)
    }

    @ViewBuilder
    private func button(for action: MemberSheetAction) -> some View {
        switch action {
        case .viewProfile:
            ConfirmButton(Text("View profile", bundle: .module)) {
                onOutcome(.contact(userUuid: userUuid))
            }
            .disabled(viewModel.isSendingRequest)

        case .answerRequest:
            ConfirmButton(Text("Answer their contact request", bundle: .module)) {
                onOutcome(.contactRequests)
            }
            .disabled(viewModel.isSendingRequest)

        case .requestSent:
            ConfirmButton(Text("Contact request sent", bundle: .module)) {}
                .disabled(true)

        case .sendRequest:
            ConfirmButton(
                Text("Send a contact request", bundle: .module),
                isLoading: viewModel.isSendingRequest,
                action: viewModel.sendContactRequest
            )

        case .remove:
            Button(role: .destructive) {
                onOutcome(.removal(userUuid: userUuid))
            } label: {
                Text("Remove from the group", bundle: .module)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .tint(AppColors.error)
            .disabled(viewModel.isSendingRequest || viewModel.isChanging)
        }
    }
}
