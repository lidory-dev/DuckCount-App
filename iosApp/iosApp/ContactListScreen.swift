import ContactList
import SwiftUI

struct ContactListScreen: View {
    @StateObject private var viewModel = ContactListViewModel()

    @State private var isAddingContact = false

    var body: some View {
        ContactListView(
            viewModel: viewModel,
            onAddContact: { isAddingContact = true }
        )
        .sheet(isPresented: $isAddingContact) {
            NewContactView(onCancel: { isAddingContact = false })
        }
    }
}
