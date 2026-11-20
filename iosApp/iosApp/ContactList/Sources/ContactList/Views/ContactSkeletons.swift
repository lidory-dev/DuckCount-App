import AppColors
import AppFonts
import SwiftUI

/// The contact list's skeleton, see `Skeleton`, as `ContactListView` lists contacts: by letter, each
/// with their picture and name.
struct ContactListSkeleton: View {
    private static let sections = [3, 4, 2]

    var body: some View {
        List {
            ForEach(Array(Self.sections.enumerated()), id: \.offset) { section, count in
                Section {
                    ForEach(0..<count, id: \.self) { contact in
                        PersonBone(name: TextBone(.body, width: Skeleton.lineWidth(section * 4 + contact)))
                    }
                } header: {
                    TextBone(.footnote)
                        .frame(width: 12)
                }
                .appCardRows()
            }
        }
        .listStyle(.insetGrouped)
        .appListBackground()
        .skeleton(.loading)
    }
}

/// The blocked users' skeleton, as `BlockedUserListView` lists them: each with their picture, their
/// name and the button that unblocks them, over what blocking does.
struct BlockedUserListSkeleton: View {
    var body: some View {
        List {
            Section {
                ForEach(0..<4, id: \.self) { index in
                    HStack(spacing: 12) {
                        PersonBone(name: TextBone(.body, width: Skeleton.lineWidth(index)))

                        ButtonBone(height: 34)
                            .frame(width: 96)
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 2) {
                    TextBone(.footnote, width: 0.95)
                    TextBone(.footnote, width: 0.6)
                }
            }
            .appCardRows()
        }
        .appListBackground()
        .skeleton(.loading)
    }
}

/// The contact requests' skeleton, as `ContactRequestListView` lists them: who sent each, over the
/// three ways to answer it.
struct ContactRequestListSkeleton: View {
    /// Whether it is laid over the list it stands for, still empty, which shows through it and can
    /// be pulled to refresh, rather than a page of its own.
    var isLaidOver = false

    var body: some View {
        if isLaidOver {
            list
                .scrollContentBackground(.hidden)
                .skeleton(.loading)
        } else {
            list
                .appListBackground()
                .skeleton(.loading)
        }
    }

    private var list: some View {
        List(0..<3, id: \.self) { index in
            VStack(alignment: .leading, spacing: 12) {
                PersonBone(name: TextBone(.body, width: Skeleton.lineWidth(index)))

                HStack(spacing: 8) {
                    ForEach(0..<3, id: \.self) { _ in
                        ButtonBone(height: 34)
                    }
                }
            }
            .padding(.vertical, 4)
            .appCardRows()
        }
    }
}

/// The safety number's skeleton, as `ContactVerificationView` shows it: the contact's name, the
/// twelve groups of the number on their card, laid out as `SafetyNumberGrid` lays them, and what to
/// do with them.
struct ContactVerificationSkeleton: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                TextBone(.title2, width: 0.5, alignment: .center)

                ViewThatFits(in: .horizontal) {
                    ForEach([4, 3, 2, 1], id: \.self) { perLine in
                        groups(perLine: perLine)
                    }
                }
                .padding(24)
                .frame(maxWidth: .infinity)
                .skeletonCard(cornerRadius: 16)

                VStack(spacing: 0) {
                    TextBone(.body, width: 0.95, alignment: .center)
                    TextBone(.body, width: 0.9, alignment: .center)
                    TextBone(.body, width: 0.55, alignment: .center)
                }
            }
            .padding()
        }
        .skeleton(.loading)
    }

    /// As `SafetyNumberGrid`'s lines of `perLine` groups of five digits.
    private func groups(perLine: Int) -> some View {
        Grid(horizontalSpacing: 20, verticalSpacing: 16) {
            ForEach(Array(stride(from: 0, to: 12, by: perLine)), id: \.self) { _ in
                GridRow {
                    ForEach(0..<perLine, id: \.self) { _ in
                        // As wide as the five digits it stands for.
                        Text(verbatim: "00000")
                            .font(.title3.monospaced())
                            .fixedSize()
                            .hidden()
                            .overlay {
                                TextBone(font: .title3.monospaced(), style: .title3)
                            }
                    }
                }
            }
        }
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
