import AppColors
import AppFonts
import SwiftUI

/// The contacts members are picked from, for a group to create or to add to one, searched by name,
/// up to the most a group may have, as Android's `GroupMemberFullScreenDialog`. No "select all":
/// the user's contacts can outnumber the limit. Shown in a sheet of its own.
struct MemberPickerView: View {
    /// Whom it lists and picks, and whether it can be confirmed as it stands, see
    /// `MemberPicker.canConfirm`, which this view reads as the user picks.
    @ObservedObject var picker: MemberPicker

    /// Whether what confirming started is under way, which the picker cannot be closed during.
    let isConfirming: Bool

    /// Why confirming failed, told over the picker, which stays open.
    @Binding var error: MembershipChangeError?

    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(picker.users) { user in
                        row(user)
                    }
                }
                .appCardRows()
            }
            .appListBackground()
            .disabled(isConfirming)
            .navigationTitle(Text("Select new members", bundle: .module))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(
                text: Binding(get: { picker.searchQuery }, set: picker.search),
                placement: .navigationBarDrawer(displayMode: .always)
            )
            .overlay {
                if picker.users.isEmpty {
                    if let nobodyToPick = picker.nobodyToPick {
                        ContentUnavailableView {
                            Label {
                                Text(nobodyToPick.message)
                            } icon: {
                                Image(systemName: "person.2.slash")
                            }
                            .font(.app(.title2))
                        }
                    } else if picker.found != nil, !picker.searchQuery.isBlank {
                        ContentUnavailableView.search(text: picker.searchQuery)
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: onCancel) {
                        Text("Cancel", bundle: .module)
                    }
                    .disabled(isConfirming)
                }
            }
            .bottomBar {
                // How many members the group would have, the user and its members included. Out of
                // the list, as Android has it, so that it stays in view while scrolling and
                // searching: the rows not picked are disabled once the limit is reached, and this
                // is what says why.
                Text("\(picker.memberCount)/\(picker.maxMembers) members", bundle: .module)
                    .font(.app(.footnote))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .monospacedDigit()

                ConfirmButton(Text("Confirm", bundle: .module), isLoading: isConfirming, action: onConfirm)
                    .disabled(!picker.canConfirm)
            }
            .alert(
                error?.message ?? "",
                isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })
            ) {
                Button(role: .cancel) {} label: {
                    Text("OK", bundle: .module)
                }
            }
        }
        // Closed while members are being added, it would cut short what comes of it.
        .interactiveDismissDisabled(isConfirming)
    }

    private func row(_ user: Person) -> some View {
        let isPicked = picker.isPicked(user.id)
        // A row picked stays enabled so that it can still be unpicked once the group is full.
        let isDisabled = !picker.isSelectable(user.id)

        return Button {
            picker.toggle(user.id)
        } label: {
            // Colors rather than the hierarchical styles, which a button's label takes from its tint.
            HStack(spacing: 12) {
                Image(systemName: isPicked ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isPicked ? AnyShapeStyle(.tint) : AnyShapeStyle(AppColors.onSurfaceVariant))

                MemberAvatar(userUuid: user.id, name: user.fullName, size: 32)

                Text(user.fullName)
                    .foregroundStyle(Color.primary)
                    .lineLimit(1)
            }
            .opacity(isDisabled ? 0.4 : 1)
        }
        .disabled(isDisabled)
        .accessibilityAddTraits(isPicked ? .isSelected : [])
    }
}
