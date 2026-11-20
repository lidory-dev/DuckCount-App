import AppColors
import AppFonts
import SwiftUI

/// Who makes DuckCount, which version this is, and under what terms, as Android's `AboutScreen`,
/// down to the duck that answers the version tapped five times in a row.
struct AboutView: View {
    private let learnMoreLink: URL
    private let appVersion: String

    /// When the version was last tapped, and how many times in a row, quickly.
    @State private var lastTap = Date.distantPast
    @State private var tapCount = 0

    /// Whether the duck answers, in place of the version, for a moment.
    @State private var isQuacking = false
    @State private var quacks = 0

    init(callBacks: any SettingsCallBacks) {
        learnMoreLink = callBacks.learnMoreLink
        appVersion = callBacks.appVersion
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    Text("DuckCount is built by the engineers at Lidory", bundle: .module)
                        .font(.app(.title3))

                    Text(
                        "Lidory is a company specializing in software development and cybersecurity. It develops both consumer applications and business software.\n\nPrivacy and cybersecurity are top priorities for us.",
                        bundle: .module
                    )
                    .foregroundStyle(AppColors.onSurfaceVariant)

                    Link(destination: learnMoreLink) {
                        Text("Learn more", bundle: .module)
                    }
                    .buttonStyle(.appProminent)
                    .padding(.top, 4)
                }
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .appCardRows()

            Section {
                LabeledContent {
                    if isQuacking {
                        Text("🐤 Quack!", bundle: .module)
                            .transition(.scale.combined(with: .opacity))
                    } else {
                        Text(verbatim: appVersion)
                            .monospacedDigit()
                    }
                } label: {
                    Text("App version", bundle: .module)
                }
                .contentShape(Rectangle())
                .onTapGesture(perform: tapVersion)
                .accessibilityAddTraits(.isStaticText)
            } footer: {
                VStack(alignment: .leading, spacing: 12) {
                    Text(
                        "DuckCount and the library on which it depends (lib-duckcount) are licensed under the European Union Public Licence, version 1.2 (EUPL-1.2).",
                        bundle: .module
                    )

                    Text("© 2026 Lidory SAS. All rights reserved.\nDuckCount® and Lidory® are trademarks of Lidory SAS.", bundle: .module)
                }
                .padding(.top, 8)
            }
            .appCardRows()
        }
        .appListBackground()
        .listStyle(.insetGrouped)
        .navigationTitle(Text("About", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: quacks)
    }

    /// Five taps, each within half a second of the last, and the duck answers.
    private func tapVersion() {
        let now = Date()

        tapCount = now.timeIntervalSince(lastTap) <= 0.5 ? tapCount + 1 : 1
        lastTap = now

        guard tapCount >= 5 else {
            return
        }

        tapCount = 0
        quacks += 1

        withAnimation(.spring) {
            isQuacking = true
        }

        let quack = quacks

        Task {
            try? await Task.sleep(for: .seconds(2))

            // Unless tapped five times again meanwhile, which keeps the duck a moment longer.
            if quacks == quack {
                withAnimation {
                    isQuacking = false
                }
            }
        }
    }
}

/// The libraries the app ships, each leading to its licences, as Android's `LicencesScreen` lists
/// them.
struct LicencesView: View {
    let libraries: [Library]

    @State private var query = ""

    var body: some View {
        let found = query.isBlank
            ? libraries
            : libraries.filter { $0.name.localizedCaseInsensitiveContains(query.trimmed) || $0.id.localizedCaseInsensitiveContains(query.trimmed) }

        List(found) { library in
            NavigationLink {
                LibraryView(library: library)
            } label: {
                LibraryRow(library: library)
            }
            .appCardRows()
        }
        .appListBackground()
        .listStyle(.insetGrouped)
        .overlay {
            if libraries.isEmpty {
                ContentUnavailableView {
                    Label {
                        Text("No licences", bundle: .module)
                    } icon: {
                        Image(systemName: "doc.text")
                    }
                    .font(.app(.title2))
                }
            } else if found.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .searchable(text: $query, prompt: Text("Search", bundle: .module))
        .navigationTitle(Text("Licences", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// A library in the list: its name and version, who makes it, and its licences.
private struct LibraryRow: View {
    let library: Library

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: library.name)
                    .lineLimit(2)

                Spacer(minLength: 8)

                if let version = library.version {
                    Text(verbatim: version)
                        .font(.app(.subheadline))
                        .monospacedDigit()
                        .foregroundStyle(AppColors.onSurfaceVariant)
                }
            }

            if let author = library.author {
                Text(verbatim: author)
                    .font(.app(.subheadline))
                    .foregroundStyle(AppColors.onSurfaceVariant)
                    .lineLimit(1)
            }

            if !library.licences.isEmpty {
                Text(verbatim: library.licences.map(\.name).joined(separator: ", "))
                    .font(.app(.caption))
                    .foregroundStyle(.tint)
            }
        }
        .padding(.vertical, 2)
    }
}

/// A library's details and the full text of its licences.
private struct LibraryView: View {
    let library: Library

    var body: some View {
        List {
            Section {
                if let version = library.version {
                    LabeledContent {
                        Text(verbatim: version)
                    } label: {
                        Text("Version", bundle: .module)
                    }
                }

                if let author = library.author {
                    LabeledContent {
                        Text(verbatim: author)
                    } label: {
                        Text("Author", bundle: .module)
                    }
                }

                if let website = library.website {
                    Link(destination: website) {
                        HStack {
                            Text("Website", bundle: .module)
                                .foregroundStyle(Color.primary)

                            Spacer()

                            Image(systemName: "arrow.up.forward")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(AppColors.outline)
                                .accessibilityHidden(true)
                        }
                    }
                }
            }
            .appCardRows()

            ForEach(library.licences, id: \.self) { licence in
                Section {
                    if let text = licence.text {
                        Text(verbatim: text)
                            .font(.app(.footnote))
                            .textSelection(.enabled)
                    } else if let url = licence.url {
                        Link(destination: url) {
                            Text("Read the licence", bundle: .module)
                        }
                    }
                } header: {
                    Text(verbatim: licence.name)
                }
                .appCardRows()
            }
        }
        .appListBackground()
        .listStyle(.insetGrouped)
        .navigationTitle(Text(verbatim: library.name))
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview("About") {
    NavigationStack {
        AboutView(callBacks: PreviewSettingsCallBacks())
    }
}

#Preview("Licences") {
    NavigationStack {
        LicencesView(libraries: PreviewSettingsCallBacks().libraries)
    }
}
