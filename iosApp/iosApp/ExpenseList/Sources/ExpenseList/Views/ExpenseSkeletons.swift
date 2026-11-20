import AppColors
import AppFonts
import SwiftUI

/// The expense lists' skeleton, see `Skeleton`, laid over a list still empty: two days of
/// expenses, each date on its card above its expenses on theirs, where `ExpenseDayHeader` and
/// `expenseCard(isDeleted:)` place them, on the skeleton's neutral cards rather than green ones.
/// On no background of its own, the list's showing through.
struct ExpenseListSkeleton: View {
    private static let days = 2
    private static let expensesPerDay = 4

    var body: some View {
        List {
            ForEach(0..<Self.days, id: \.self) { day in
                Section {
                    dayHeader(day)

                    ForEach(0..<Self.expensesPerDay, id: \.self) { expense in
                        expenseRow(day * Self.expensesPerDay + expense)
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .skeleton(.loading)
    }

    /// As `ExpenseDayHeader` within `dateRow(of:in:)`.
    private func dayHeader(_ day: Int) -> some View {
        TextBone(.body, width: Skeleton.lineWidth(day + 1) * 0.6)
            .padding(8)
            .skeletonCard(cornerRadius: ExpenseCard.cornerRadius)
            .padding([.top, .horizontal], 4)
            .padding(.bottom, 12)
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    /// As `ExpenseRow` within `expenseCard(isDeleted:)`: what it was for over who paid, and its
    /// amount over the user's share.
    private func expenseRow(_ index: Int) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                TextBone(.title2, width: Skeleton.lineWidth(index))
                TextBone(.caption, width: Skeleton.lineWidth(index + 3) * 0.7)
            }

            VStack(alignment: .trailing, spacing: 2) {
                TextBone(.title2, alignment: .trailing)
                TextBone(.subheadline, width: 0.7, alignment: .trailing)
            }
            .frame(width: 88)
        }
        .listRowInsets(ExpenseCard.contentInsets)
        .listRowSeparator(.hidden)
        .listRowBackground(
            Color.clear
                .skeletonCard(cornerRadius: ExpenseCard.cornerRadius)
                .padding(ExpenseCard.margins)
        )
    }
}

/// An expense's skeleton, as `ExpenseDetailView` lays it out: what it was for, who paid it and who
/// shares it.
struct ExpenseDetailSkeleton: View {
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    TextBone(.title2, width: 0.55)
                    TextBone(.body, width: 0.8)
                }
                .padding(.vertical, 4)
            }
            .appCardRows()

            Section {
                PersonAmountBone(index: 0)
            } header: {
                SectionHeaderBone(width: 56)
            }
            .appCardRows()

            Section {
                ForEach(1..<4, id: \.self) { index in
                    PersonAmountBone(index: index)
                }
            } header: {
                SectionHeaderBone(width: 88)
            }
            .appCardRows()
        }
        .appListBackground()
        .skeleton(.loading)
    }
}

/// An expense's history's skeleton, as `ExpenseHistoryView` lists its versions: when each was made
/// over who made it.
struct ExpenseHistorySkeleton: View {
    var body: some View {
        List(0..<6, id: \.self) { index in
            VStack(alignment: .leading, spacing: 2) {
                TextBone(.headline, width: Skeleton.lineWidth(index))
                TextBone(.subheadline, width: Skeleton.lineWidth(index + 2) * 0.8)
            }
            .padding(.vertical, 2)
            .appCardRows()
        }
        .appListBackground()
        .skeleton(.loading)
    }
}

/// As `PersonAmountRow`: a person and their amount.
private struct PersonAmountBone: View {
    let index: Int

    var body: some View {
        HStack(spacing: 12) {
            PersonBone(avatarSize: 32, name: TextBone(.body, width: Skeleton.lineWidth(index)))

            TextBone(.body, alignment: .trailing)
                .frame(width: 64)
        }
    }
}

/// A section's header, `width` long.
private struct SectionHeaderBone: View {
    let width: CGFloat

    var body: some View {
        TextBone(.footnote)
            .frame(width: width)
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
