import AppColors
import AppFonts
import SwiftUI

/// The versions of a group's title and description, newest first, each with when it was made, the
/// one the group shows marked, as Android's `GroupHistoryScreen`. Read from the server, again on
/// demand once it could not be.
struct GroupHistoryView: View {
    @StateObject private var viewModel: GroupHistoryViewModel

    init(groupId: String, callBacks: any GroupCallBacks) {
        _viewModel = StateObject(wrappedValue: GroupHistoryViewModel(groupId: groupId, callBacks: callBacks))
    }

    var body: some View {
        Group {
            switch viewModel.state {
            case .loading:
                ProgressView()

            case .failed(let error):
                ContentUnavailableView {
                    Label {
                        Text(error.message)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle")
                    }
                    .font(.app(.title2))
                } actions: {
                    Button(action: viewModel.retry) {
                        Text("Try again", bundle: .module)
                    }
                    .buttonStyle(.bordered)
                }

            case .loaded(let versions):
                List(versions) { version in
                    GroupVersionRow(version: version)
                        .appCardRows()
                }
                .appListBackground()
            }
        }
        .navigationTitle(Text("Group history", bundle: .module))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear(perform: viewModel.load)
    }
}

/// One version of a group's title and description, as Android's `GroupVersionCard`: its title, its
/// description when it has one, and when the group was created as it, or edited to it.
struct GroupVersionRow: View {
    let version: GroupVersion

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(version.title)
                    .font(.app(.headline))
                    .frame(maxWidth: .infinity, alignment: .leading)

                if version.isCurrent {
                    Text("Current version", bundle: .module)
                        .font(.app(.caption))
                        .foregroundStyle(AppColors.onPrimaryContainer)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(AppColors.primaryContainer, in: Capsule())
                }
            }

            if !version.description.isBlank {
                Text(version.description)
                    .font(.app(.subheadline))
            }

            madeOn
                .font(.app(.footnote))
                .italic()
                .foregroundStyle(AppColors.onSurfaceVariant)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    /// When the group was created as this version, or edited to it, its date and time as the
    /// user's language writes them.
    private var madeOn: Text {
        let date = version.revisedAt.formatted(date: .abbreviated, time: .shortened)

        return version.isCreation
            ? Text("Created on \(date)", bundle: .module)
            : Text("Edited on \(date)", bundle: .module)
    }
}
