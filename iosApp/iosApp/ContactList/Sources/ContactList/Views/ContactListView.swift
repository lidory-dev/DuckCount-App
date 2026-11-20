import AppColors
import AppFonts
import SwiftUI

/// Where the contacts tab leads.
enum ContactRoute: Hashable {
    case contact(String)
    case requests
    case blockedUsers
}

/// What the app asks the contacts tab to open, a notification about it having been tapped: the
/// requests waiting for the user, as Android's intent opens them, or a contact who just accepted one.
public enum ContactListOpening: Equatable, Sendable {
    case requests
    case contact(String)
}

/// The user's contacts by letter, the requests waiting for them, the people they blocked, and the
/// way to add somebody, once they reserved a username: the Contacts tab, as Android's
/// `ContactListScreen`.
public struct ContactListView: View {
    private let callBacks: any ContactCallBacks

    /// The form a username is reserved with, which the settings provide, shown in a sheet that closes
    /// itself once the username is reserved, as Android's contact list opens its settings on it.
    private let usernameReservation: @MainActor () -> AnyView

    @StateObject private var viewModel: ContactListViewModel

    /// What the app asks to open, see `ContactListOpening`: opened once, then cleared.
    @Binding private var opening: ContactListOpening?

    @State private var selection: ContactRoute?
    @State private var isAddingContact = false
    @State private var isReservingUsername = false

    @Environment(\.scenePhase) private var scenePhase

    public init(
        callBacks: any ContactCallBacks,
        usernameReservation: @escaping @MainActor () -> AnyView,
        opening: Binding<ContactListOpening?> = .constant(nil)
    ) {
        self.callBacks = callBacks
        self.usernameReservation = usernameReservation
        _opening = opening
        _viewModel = StateObject(wrappedValue: ContactListViewModel(callBacks: callBacks))
    }

    public var body: some View {
        NavigationSplitView {
            sidebar
                .appBackground()
                .navigationTitle(Text("Contacts", bundle: .module))
                .toolbar { toolbar }
        } detail: {
            NavigationStack {
                detail
                    .appBackground()
            }
        }
        .sheet(isPresented: $isAddingContact) {
            NewContactView(callBacks: callBacks)
        }
        .sheet(isPresented: $isReservingUsername) {
            usernameReservation()
        }
        .onChange(of: opening, initial: true) { _, request in
            switch request {
            case .requests: selection = .requests
            case .contact(let userUuid): selection = .contact(userUuid)
            case nil: return
            }

            opening = nil
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

    @ViewBuilder
    private var sidebar: some View {
        switch viewModel.state {
        case .loading:
            ProgressView()

        case .usernameReservationRequired:
            ContentUnavailableView {
                Label {
                    Text("Reserve a username", bundle: .module)
                } icon: {
                    Image(systemName: "at")
                }
                .font(.app(.title2))
            } description: {
                Text(
                    "Your contacts need a username to add you, and you need one to add them.\n\nReserve yours to start building your contact list.",
                    bundle: .module
                )
                .font(.app(.body))
            } actions: {
                Button {
                    isReservingUsername = true
                } label: {
                    Text("Reserve a username", bundle: .module)
                }
                .buttonStyle(.appProminent)
            }

        case .content(let content):
            list(content)
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        // Nobody can be added before a username is reserved.
        if case .content = viewModel.state {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    isAddingContact = true
                } label: {
                    Label {
                        Text("Add a new contact", bundle: .module)
                    } icon: {
                        Image(systemName: "person.badge.plus")
                    }
                }
            }
        }
    }

    private func list(_ content: ContactListViewModel.Content) -> some View {
        let isSearching = !viewModel.searchQuery.isBlank
        // The requests and blocked users rows, which an empty state laid over the list would cover.
        let hasOtherRows = !isSearching && (content.contactRequestCount > 0 || content.blockedUserCount > 0)

        return List(selection: $selection) {
            if !isSearching, content.contactRequestCount > 0 {
                Section {
                    NavigationLink(value: ContactRoute.requests) {
                        Label {
                            Text("Contact requests", bundle: .module)
                        } icon: {
                            Image(systemName: "envelope.badge")
                        }
                    }
                    .badge(content.contactRequestCount)
                    .foregroundStyle(AppColors.onSecondaryContainer)
                }
                // As Android's card for them stands out from the contacts.
                .listRowBackground(AppColors.secondaryContainer)
            }

            ForEach(content.sections) { section in
                Section {
                    ForEach(section.contacts) { contact in
                        NavigationLink(value: ContactRoute.contact(contact.id)) {
                            HStack(spacing: 12) {
                                ContactAvatar(userUuid: contact.id, name: contact.displayName)

                                Text(contact.displayName)
                                    .lineLimit(1)
                            }
                        }
                    }
                } header: {
                    // Letters while browsing, none while searching.
                    if !isSearching {
                        Text(verbatim: section.id)
                    }
                }
                .appCardRows()
            }

            // Between the other rows, as Android lays it out, rather than over them.
            if content.sections.isEmpty, hasOtherRows {
                Section {
                    noContacts
                        .listRowBackground(Color.clear)
                }
                .appCardRows()
            }

            // At the end, out of the way: blocking is rare, and nothing about it is urgent. Not while
            // searching, which finds contacts alone.
            if !isSearching, content.blockedUserCount > 0 {
                Section {
                    NavigationLink(value: ContactRoute.blockedUsers) {
                        Label {
                            Text("Blocked users", bundle: .module)
                        } icon: {
                            Image(systemName: "nosign")
                        }
                    }
                    .badge(content.blockedUserCount)
                }
                .appCardRows()
            }
        }
        .listStyle(.insetGrouped)
        .searchable(
            text: Binding(get: { viewModel.searchQuery }, set: viewModel.search),
            prompt: Text("Search", bundle: .module)
        )
        .refreshable {
            await viewModel.refresh()
        }
        .overlay {
            if content.sections.isEmpty, !hasOtherRows {
                // Nobody whose name holds what is searched, or nobody at all to find.
                if content.hasContacts {
                    ContentUnavailableView.search(text: viewModel.searchQuery)
                } else {
                    noContacts
                }
            }
        }
    }

    private var noContacts: some View {
        ContentUnavailableView {
            Label {
                Text("You don’t have any contacts yet", bundle: .module)
            } icon: {
                Image(systemName: "person.2")
            }
            .font(.app(.title2))
        } actions: {
            Button {
                isAddingContact = true
            } label: {
                Text("Add a new contact", bundle: .module)
            }
        }
    }

    @ViewBuilder
    private var detail: some View {
        switch selection {
        case .contact(let id):
            ContactDetailView(contactId: id, callBacks: callBacks) {
                selection = nil
            }
            .id(id)

        case .requests:
            ContactRequestListView(callBacks: callBacks)

        case .blockedUsers:
            BlockedUserListView(callBacks: callBacks)

        case nil:
            ContentUnavailableView {
                Label {
                    Text("No Contact Selected", bundle: .module)
                } icon: {
                    Image(systemName: "person.crop.circle")
                }
                .font(.app(.title2))
            }
        }
    }
}

#Preview {
    ContactListView(callBacks: PreviewContactCallBacks(), usernameReservation: { AnyView(Text(verbatim: "Username")) })
}
