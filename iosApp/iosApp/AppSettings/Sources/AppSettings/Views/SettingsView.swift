import AppFonts
import SwiftUI

/// Where the settings list leads.
enum SettingsRoute: Hashable {
    case profile
    case username
    case account
    case about
    case licences
}

/// The user's profile, the username they can be found by, their account, and what there is to know
/// about the app: the Settings tab, as Android's `SettingsScreen`, laid out as the system's own
/// settings are, a list beside its detail on a large screen.
public struct SettingsView: View {
    private let callBacks: any SettingsCallBacks

    @StateObject private var viewModel: SettingsViewModel

    @State private var selection: SettingsRoute?

    public init(callBacks: any SettingsCallBacks) {
        self.callBacks = callBacks
        _viewModel = StateObject(wrappedValue: SettingsViewModel(callBacks: callBacks))
    }

    public var body: some View {
        NavigationSplitView {
            list
                .navigationTitle(Text("Settings", bundle: .module))
        } detail: {
            NavigationStack {
                detail
            }
        }
    }

    private var list: some View {
        List(selection: $selection) {
            Section {
                NavigationLink(value: SettingsRoute.profile) {
                    ProfileHeader(
                        userUuid: viewModel.userUuid ?? "",
                        name: viewModel.displayName
                    )
                }
            }

            Section {
                NavigationLink(value: SettingsRoute.username) {
                    LabeledContent {
                        if let reservedUsername = viewModel.reservedUsername {
                            Text(verbatim: reservedUsername)
                        }
                    } label: {
                        Label {
                            Text("Username", bundle: .module)
                        } icon: {
                            Image(systemName: "at")
                        }
                    }
                }

                NavigationLink(value: SettingsRoute.account) {
                    Label {
                        Text("Account", bundle: .module)
                    } icon: {
                        Image(systemName: "person.badge.key")
                    }
                }
            }

            Section {
                NavigationLink(value: SettingsRoute.about) {
                    Label {
                        Text("About", bundle: .module)
                    } icon: {
                        Image(systemName: "info.circle")
                    }
                }

                NavigationLink(value: SettingsRoute.licences) {
                    Label {
                        Text("Licences", bundle: .module)
                    } icon: {
                        Image(systemName: "doc.text")
                    }
                }
            }

            Section {
                ExternalLinkRow(url: callBacks.sourceCodeLink, systemImage: "chevron.left.forwardslash.chevron.right") {
                    Text("Source code", bundle: .module)
                }

                ExternalLinkRow(url: callBacks.termsOfUseLink, systemImage: "doc.plaintext") {
                    Text("Terms of use", bundle: .module)
                }

                ExternalLinkRow(url: callBacks.privacyPolicyLink, systemImage: "hand.raised") {
                    Text("Privacy policy", bundle: .module)
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    @ViewBuilder
    private var detail: some View {
        switch selection {
        case .profile:
            ProfileView(callBacks: callBacks)

        case .username:
            // The reservation is the only thing the screen is for, so a successful one hands the
            // user back to the list, as on Android.
            ReserveUsernameView(callBacks: callBacks) {
                selection = nil
            }

        case .account:
            AccountView(callBacks: callBacks)

        case .about:
            AboutView(callBacks: callBacks)

        case .licences:
            LicencesView(libraries: callBacks.libraries)

        case nil:
            ContentUnavailableView {
                Label {
                    Text("Settings", bundle: .module)
                } icon: {
                    Image(systemName: "gearshape")
                }
                .font(.app(.title2))
            }
        }
    }
}

/// Who the user is, at the top of the list, as the system's settings name the account they are
/// signed in with: the avatar their contacts see, and their name.
private struct ProfileHeader: View {
    let userUuid: String
    let name: String

    var body: some View {
        HStack(spacing: 14) {
            InitialAvatar(userUuid: userUuid, name: name, size: 56)

            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name)
                    .font(.app(.title3))
                    .lineLimit(2)

                Text("Profile", bundle: .module)
                    .font(.app(.subheadline))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

/// A row that opens `url` in the browser, marked as leaving the app, as the system's settings mark
/// such rows.
private struct ExternalLinkRow<Title: View>: View {
    let url: URL
    let systemImage: String
    @ViewBuilder let title: () -> Title

    var body: some View {
        Link(destination: url) {
            HStack {
                Label {
                    title()
                        .foregroundStyle(Color.primary)
                } icon: {
                    Image(systemName: systemImage)
                }

                Spacer()

                Image(systemName: "arrow.up.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
    }
}

#Preview {
    SettingsView(callBacks: PreviewSettingsCallBacks())
}
