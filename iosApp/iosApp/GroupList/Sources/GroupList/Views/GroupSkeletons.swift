import AppColors
import AppFonts
import SwiftUI

/// The group list's skeleton, see `Skeleton`, laid over the list still empty, as `GroupRow` shows
/// each group: its title over its description.
struct GroupListSkeleton: View {
    var body: some View {
        List {
            ForEach(0..<6, id: \.self) { index in
                VStack(alignment: .leading, spacing: 2) {
                    TextBone(.headline, width: Skeleton.lineWidth(index))
                    TextBone(.subheadline, width: Skeleton.lineWidth(index + 3))
                }
                .padding(.vertical, 2)
            }
            .appCardRows()
        }
        .scrollContentBackground(.hidden)
        .skeleton(.loading)
    }
}

/// A group's settings' skeleton, laid over their list still empty, as `GroupSettingsView` lays them
/// out: the group's title and description, its members, and what the user can do about it.
struct GroupSettingsSkeleton: View {
    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    TextBone(.title, width: 0.5, alignment: .center)
                    TextBone(.body, width: 0.7, alignment: .center)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }

            // On iOS's own row colour, as the members' section is.
            Section {
                ForEach(0..<4, id: \.self) { index in
                    PersonBone(name: TextBone(.body, width: Skeleton.lineWidth(index)))
                }
            } header: {
                TextBone(.footnote)
                    .frame(width: 80)
            }

            Section {
                HStack(spacing: 16) {
                    Bone()
                        .frame(width: 22, height: 22)

                    TextBone(.body, width: 0.45)
                }
            }
            .appCardRows()
        }
        .scrollContentBackground(.hidden)
        .skeleton(.loading)
    }
}

/// A group's history's skeleton, as `GroupHistoryView` lists its versions: each title, description,
/// and when it was made.
struct GroupHistorySkeleton: View {
    var body: some View {
        List(0..<5, id: \.self) { index in
            VStack(alignment: .leading, spacing: 6) {
                TextBone(.headline, width: Skeleton.lineWidth(index))
                TextBone(.subheadline, width: Skeleton.lineWidth(index + 2))
                TextBone(.footnote, width: 0.45)
            }
            .padding(.vertical, 4)
            .appCardRows()
        }
        .appListBackground()
        .skeleton(.loading)
    }
}

/// The Balance tab's skeleton, laid over its list still empty, as `BalanceBarRow` draws each
/// person's balance: who is owed first, their bars to the right of the middle, then who owes, theirs
/// to the left.
struct BalanceSkeleton: View {
    /// Whether each row owes, and its bar's length against the longest.
    private static let rows: [(isOwing: Bool, fraction: CGFloat)] = [
        (false, 0.9), (false, 0.5), (false, 0.25), (true, 0.2), (true, 0.55), (true, 0.85),
    ]

    var body: some View {
        List(Self.rows.indices, id: \.self) { index in
            row(index)
                .listRowSeparator(.hidden)
                .listRowInsets(EdgeInsets(top: 4, leading: 8, bottom: 4, trailing: 8))
                .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .skeleton(.loading)
    }

    private func row(_ index: Int) -> some View {
        let (isOwing, fraction) = Self.rows[index]

        let name = TextBone(.body, width: Skeleton.lineWidth(index), alignment: isOwing ? .leading : .trailing)
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity)

        let bar = GeometryReader { geometry in
            UnevenRoundedRectangle(
                topLeadingRadius: isOwing ? 8 : 0,
                bottomLeadingRadius: isOwing ? 8 : 0,
                bottomTrailingRadius: isOwing ? 0 : 8,
                topTrailingRadius: isOwing ? 0 : 8,
                style: .continuous
            )
            .fill(Skeleton.boneColor)
            .frame(width: geometry.size.width * fraction)
            .frame(width: geometry.size.width, height: geometry.size.height, alignment: isOwing ? .trailing : .leading)
        }

        return HStack(spacing: 0) {
            if isOwing {
                bar
                name
            } else {
                name
                bar
            }
        }
        .frame(height: 40)
    }
}

/// The Settle up tab's skeleton, laid over its list still empty, as `SettleUpTabView` words each
/// repayment, a line of its own.
struct SettleUpSkeleton: View {
    var body: some View {
        List(0..<4, id: \.self) { index in
            TextBone(.body, width: min(0.95, Skeleton.lineWidth(index) + 0.25))
                .padding(.vertical, 6)
                .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .skeleton(.loading)
    }
}

/// The skeleton of a group's form while the group is read into it, as `GroupFormView` lays out the
/// edit of a group: its title, then its description.
struct GroupFormSkeleton: View {
    var body: some View {
        Form {
            Section {
                TextBone(.body, width: 0.5)
            }
            .appCardRows()

            Section {
                TextBone(.body, width: 0.8)
            }
            .appCardRows()
        }
        .appListBackground()
        .skeleton(.loading)
    }
}

extension TextBone {
    /// A line of text in the app's font for `style`, see `TextBone`.
    init(_ style: Font.TextStyle, width: CGFloat = 1, alignment: HorizontalAlignment = .leading) {
        self.init(font: .app(style), style: style, width: width, alignment: alignment)
    }
}

extension Text {
    /// What VoiceOver reads a skeleton as.
    static var loading: Text {
        Text("Loading", bundle: .module)
    }
}
