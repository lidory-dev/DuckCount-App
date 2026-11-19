import AppSettings
import SwiftUI

/// The Settings tab, run by the shared module.
struct SettingsScreen: View {
    @State private var callBacks = SettingsCallBacksImpl()

    var body: some View {
        SettingsView(callBacks: callBacks)
    }
}
