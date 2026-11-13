import ContactList
import SwiftUI

/// The Contacts tab, run by the shared module.
struct ContactListScreen: View {
    @State private var callBacks = ContactCallBacksImpl()

    var body: some View {
        ContactListView(callBacks: callBacks)
    }
}
