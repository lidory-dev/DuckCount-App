import AppSettings
import ContactList
import SwiftUI

/// The Contacts tab, run by the shared module, which asks a user with no username yet to reserve one
/// with the settings' form.
struct ContactListScreen: View {
    @State private var callBacks = ContactCallBacksImpl()
    @State private var settingsCallBacks = SettingsCallBacksImpl()

    /// What a tapped notification asks the tab to open.
    @ObservedObject private var route = NotificationRoute.shared

    var body: some View {
        ContactListView(
            callBacks: callBacks,
            usernameReservation: { [settingsCallBacks] in
                AnyView(ReserveUsernameSheet(callBacks: settingsCallBacks))
            },
            opening: $route.contacts
        )
    }
}
