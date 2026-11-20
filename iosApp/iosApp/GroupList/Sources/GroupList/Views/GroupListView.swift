import AppColors
import AppFonts
import SwiftUI

/// Every group on the device, which opens on its own screen, and the way to create one: the Groups
/// tab, as Android's `GroupListScreen`.
public struct GroupListView: View {
    private let callBacks: any GroupCallBacks
    private let destinations: GroupDestinations

    @StateObject private var viewModel: GroupListViewModel

    /// The group, and the expense of it, the app asks to open: opened once, then cleared.
    @Binding private var opening: GroupOpening?

    /// The expense of the group opened the group's expenses are to open, see `opening`.
    @State private var openingExpense: String?

    @State private var selection: String?
    @State private var isCreating = false

    /// The group just created, opened once its form is gone.
    @State private var createdGroupId: String?

    /// Whether the settings of the group shown have a change on its way, see
    /// `isGroupChangeUnderWay`.
    @State private var isChangeUnderWay = false

    @Environment(\.scenePhase) private var scenePhase

    public init(
        callBacks: any GroupCallBacks,
        destinations: GroupDestinations,
        opening: Binding<GroupOpening?> = .constant(nil)
    ) {
        self.callBacks = callBacks
        self.destinations = destinations
        _opening = opening
        _viewModel = StateObject(wrappedValue: GroupListViewModel(callBacks: callBacks))
    }

    public var body: some View {
        NavigationSplitView {
            list
                .appBackground()
                .navigationTitle(Text("Groups", bundle: .module))
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            isCreating = true
                        } label: {
                            Label {
                                Text("Create a group", bundle: .module)
                            } icon: {
                                Image(systemName: "plus")
                            }
                        }
                        // The group created would be opened in place of the one changing.
                        .disabled(isChangeUnderWay)
                    }
                }
        } detail: {
            NavigationStack {
                if let selection {
                    GroupDetailView(
                        groupId: selection,
                        callBacks: callBacks,
                        destinations: destinations,
                        openingExpense: $openingExpense
                    ) {
                        // Gone from the device: nothing left to show.
                        if self.selection == selection {
                            self.selection = nil
                        }
                    }
                    .appBackground()
                } else {
                    ContentUnavailableView {
                        Label {
                            Text("No Group Selected", bundle: .module)
                        } icon: {
                            Image(systemName: "person.3")
                        }
                        .font(.app(.title2))
                    }
                    .appBackground()
                }
            }
            // A stack of its own for each group, which drops what was pushed over the last one.
            .id(selection)
        }
        .environment(\.isGroupChangeUnderWay, $isChangeUnderWay)
        .sheet(isPresented: $isCreating, onDismiss: openCreatedGroup) {
            GroupFormView(callBacks: callBacks, mode: .create) { groupId in
                createdGroupId = groupId
            }
        }
        .onChange(of: opening, initial: true) { _, request in
            guard let request else {
                return
            }

            opening = nil

            // A change of the group shown keeps it shown until answered, see `isGroupChangeUnderWay`.
            guard !isChangeUnderWay else {
                return
            }

            selection = request.groupId
            openingExpense = request.expenseId
        }
        .task {
            await viewModel.refresh()
        }
        // No push reaches iOS yet, so coming back to the app is when what changed meanwhile is read.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await viewModel.refresh()
                }
            }
        }
    }

    private var list: some View {
        // Kept while a change of the group shown is on its way, where the sidebar shows beside it.
        List(selection: Binding(get: { selection }, set: { if !isChangeUnderWay { selection = $0 } })) {
            ForEach(viewModel.groups ?? []) { group in
                NavigationLink(value: group.id) {
                    GroupRow(group: group)
                }
            }
            .appCardRows()
        }
        .appListBackground()
        .refreshable {
            await viewModel.refresh()
        }
        .overlay {
            if let groups = viewModel.groups {
                if groups.isEmpty {
                    ContentUnavailableView {
                        Label {
                            Text("You are not part of any group", bundle: .module)
                        } icon: {
                            Image(systemName: "person.3")
                        }
                        .font(.app(.title2))
                    } actions: {
                        Button {
                            isCreating = true
                        } label: {
                            Text("Create a group", bundle: .module)
                        }
                    }
                }
            } else {
                ProgressView()
            }
        }
    }

    /// Opens the group just created in place of its form, as Android replaces the one with the
    /// other: Back from it leads to the list.
    private func openCreatedGroup() {
        if let createdGroupId {
            selection = createdGroupId
            self.createdGroupId = nil
        }
    }
}

extension EnvironmentValues {
    /// Whether a change made from a group's settings is on its way, which they set while it is, for
    /// the list of groups to keep that group selected meanwhile: another one selected would close
    /// the settings, and a failure would go untold, as Android's confirmation keeps every other
    /// screen out of reach until the change is answered.
    @Entry var isGroupChangeUnderWay: Binding<Bool> = .constant(false)
}

/// A group in the list: its title and description, struck through and dimmed once the user is no
/// longer in it, which still opens it, read-only, as Android's `GroupCard`.
struct GroupRow: View {
    let group: GroupSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(group.title)
                .font(.app(.headline))
                .strikethrough(group.isLeft)
                .lineLimit(1)

            if !group.description.isBlank {
                Text(group.description)
                    .font(.app(.subheadline))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .strikethrough(group.isLeft)
                    .lineLimit(1)
            }
        }
        .opacity(group.isLeft ? 0.6 : 1)
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        // What the line through it says, which is not read out.
        .accessibilityValue(GroupNotices.marker(isLeft: group.isLeft, isDeleted: group.isDeleted) ?? "")
    }
}

#Preview {
    GroupListView(callBacks: PreviewGroupCallBacks(), destinations: PreviewGroupCallBacks.destinations)
}
