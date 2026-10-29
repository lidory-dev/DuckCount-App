import SwiftUI

/// Searchable, alphabetically grouped list of contacts.
public struct ContactListView<ViewModel: ContactListState>: View {
    @ObservedObject private var viewModel: ViewModel
    private let onAddContact: (() -> Void)?

    @State private var selectedContact: Contact?
    @State private var navigationPath = NavigationPath()

    public init(viewModel: ViewModel, onAddContact: (() -> Void)? = nil) {
        self.viewModel = viewModel
        self.onAddContact = onAddContact
    }

    private var isBigScreen: Bool {
        UIDevice.current.userInterfaceIdiom != .phone
    }

    private var isSearching: Bool {
        !viewModel.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public var body: some View {
        if isBigScreen {
            NavigationSplitView {
                listContent
                    .navigationTitle("Contacts")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { addContactToolbarItem }
                    .searchable(text: $viewModel.searchQuery, prompt: "Search contacts")
            } detail: {
                if let selectedContact {
                    ContactDetailView(contact: selectedContact)
                } else {
                    Text("Choose a contact to display more information about it.")
                        .foregroundStyle(.secondary)
                }
            }
        } else {
            NavigationStack(path: $navigationPath) {
                listContent
                    .navigationTitle("Contacts")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { addContactToolbarItem }
                    .searchable(text: $viewModel.searchQuery, prompt: "Search contacts")
                    .navigationDestination(for: Contact.self) { contact in
                        ContactDetailView(contact: contact)
                    }
            }
        }
    }

    @ToolbarContentBuilder
    private var addContactToolbarItem: some ToolbarContent {
        if let onAddContact {
            ToolbarItem(placement: .primaryAction) {
                Button(action: onAddContact) {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add Contact")
            }
        }
    }

    @ViewBuilder
    private var listContent: some View {
        if viewModel.isLoading {
            ProgressView()
        } else {
            let sections = groupedSections(viewModel.contacts)

            List {
                if isSearching {
                    // Matches Android: letter headers are hidden while searching.
                    ForEach(sections.flatMap { $0.contacts }) { contact in
                        contactRow(contact)
                    }
                } else {
                    ForEach(sections) { section in
                        Section {
                            ForEach(section.contacts) { contact in
                                contactRow(contact)
                            }
                        } header: {
                            Text(section.letter)
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .overlay {
                if viewModel.contacts.isEmpty {
                    ContentUnavailableView(
                        "No contacts",
                        systemImage: "person.crop.circle.badge.questionmark"
                    )
                }
            }
        }
    }

    @ViewBuilder
    private func contactRow(_ contact: Contact) -> some View {
        ContactRowView(contact: contact)
            .listRowSeparator(.hidden)
            .onTapGesture {
                if isBigScreen {
                    selectedContact = contact
                } else {
                    navigationPath.append(contact)
                }
            }
    }

    // MARK: - Grouping (port of ContactListScreen.kt sortedWith/groupBy)

    private struct ContactSection: Identifiable {
        let id: String
        let letter: String
        let contacts: [Contact]
    }

    private func groupedSections(_ contacts: [Contact]) -> [ContactSection] {
        Dictionary(grouping: contacts) { sectionKey(for: $0.fullName) }
            .map { key, value in
                ContactSection(
                    id: key,
                    letter: key,
                    contacts: value.sorted {
                        $0.fullName.localizedCaseInsensitiveCompare($1.fullName) == .orderedAscending
                    }
                )
            }
            .sorted { lhs, rhs in
                // Non-letter group ("#") always sorts first.
                if lhs.letter == rhs.letter { return false }
                if lhs.letter == "#" { return true }
                if rhs.letter == "#" { return false }
                return lhs.letter < rhs.letter
            }
    }

    /// Uppercased, diacritic-folded first letter; non-letters bucket into "#".
    private func sectionKey(for fullName: String) -> String {
        guard let first = fullName.first else {
            return "#"
        }

        let folded = String(first).folding(options: .diacriticInsensitive, locale: .current)
        guard let foldedFirst = folded.first, foldedFirst.isLetter else {
            return "#"
        }

        return String(foldedFirst).uppercased()
    }
}

private final class PreviewContactListState: ContactListState {
    @Published var contacts: [Contact]
    @Published var isLoading: Bool = false
    @Published var searchQuery: String = ""

    init(contacts: [Contact]) {
        self.contacts = contacts
    }
}

#Preview {
    ContactListView(
        viewModel: PreviewContactListState(
            contacts: [
                Contact(id: "1", username: "alice", fullName: "Alice Martin"),
                Contact(id: "2", username: "bob", fullName: "Bob Johnson"),
                Contact(id: "3", username: "alex", fullName: "Álex Núñez"),
                Contact(id: "4", username: "charlie", fullName: "Charlie Davis"),
                Contact(id: "5", username: "_42", fullName: "42 Robot"),
            ]
        ),
        onAddContact: {}
    )
}
