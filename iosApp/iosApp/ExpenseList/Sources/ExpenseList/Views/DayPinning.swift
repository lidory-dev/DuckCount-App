import AppColors
import Observation
import SwiftUI

/// The day the top of a list of expenses is in, and how far the next day's date has pushed its date
/// up, as Android's `stickyHeader` pins a day's date: worked out from where the rows are, the dates
/// being rows of their own, see `pinnedDayOverlay(_:)`.
///
/// iOS's own pinned headers fade the date the next one pushes out, letting the expense behind it show
/// through for a moment, so the lists pin theirs themselves: one date, laid over the list, opaque
/// throughout, and only it is drawn again as the list scrolls.
@MainActor
@Observable
final class DayPinning {
    /// The date pinned at the top of the list, and how far up the next one pushes it.
    struct Pinned: Equatable {
        let title: String
        let offset: CGFloat
    }

    /// Nil at the top of the list, where the first day's date shows in its place.
    private(set) var pinned: Pinned?

    /// Whether the list is scrolled past its top, its first date having gone above it: never while a
    /// large title shows above the list, which shrinks into the bar before any row goes under it.
    private(set) var isScrolledPastTop = false

    private struct Row {
        let dayId: String
        let isDate: Bool
        let frame: CGRect
    }

    @ObservationIgnored private var dayIds: [String] = []
    @ObservationIgnored private var titles: [String: String] = [:]
    @ObservationIgnored private var rows: [String: Row] = [:]
    @ObservationIgnored private var dateHeight: CGFloat = 0
    @ObservationIgnored private var isTopCheckDue = false

    /// Where the list's top is on the screen, which the rows are measured from: a coordinate space
    /// named on a list reaches none of its rows.
    @ObservationIgnored private var listTop: CGFloat = 0

    /// The list's days, newest first, each with its title.
    func setDays(_ days: [(id: String, title: String)]) {
        dayIds = days.map(\.id)
        titles = Dictionary(days.map { ($0.id, $0.title) }, uniquingKeysWith: { first, _ in first })
        rows = rows.filter { titles[$0.value.dayId] != nil }
        settle()
    }

    /// Where the list's top is on the screen.
    func placeList(top: CGFloat) {
        listTop = top
        settle()
    }

    /// Where the row `rowId`, of the day `dayId`, is on the screen, the day's date or one of its
    /// expenses.
    func place(rowId: String, dayId: String, isDate: Bool, frame: CGRect) {
        rows[rowId] = Row(dayId: dayId, isDate: isDate, frame: frame)

        if isDate {
            dateHeight = frame.height
        }

        settle()
    }

    /// The row `rowId` is no longer laid out.
    func remove(rowId: String) {
        rows[rowId] = nil
        settle()
    }

    private func settle() {
        let pinned = pinnedNow()

        if pinned != self.pinned {
            self.pinned = pinned
        }

        checkTopOnceAllPlaced()
    }

    /// Works out `isScrolledPastTop` once every row has said where it is: the list's top and the
    /// rows tell one by one, the top first as a large title grows, which for a moment would have the
    /// first date above it, and the title hidden.
    private func checkTopOnceAllPlaced() {
        guard !isTopCheckDue else {
            return
        }

        isTopCheckDue = true

        Task { @MainActor in
            isTopCheckDue = false

            let isScrolledPastTop = isPastTopNow()

            if isScrolledPastTop != self.isScrolledPastTop {
                self.isScrolledPastTop = isScrolledPastTop
            }
        }
    }

    private func isPastTopNow() -> Bool {
        guard let firstDay = dayIds.first else {
            return false
        }

        // Gone out of the list once scrolled far enough past the top, unless nothing is laid out yet.
        guard let date = rows["date:" + firstDay] else {
            return !rows.isEmpty
        }

        // A point of leeway, positions coming in fractions of one.
        return date.frame.minY < listTop - 1
    }

    private func pinnedNow() -> Pinned? {
        // Measured from the list's top, the first row still showing there.
        let rows = rows.values.map { Row(dayId: $0.dayId, isDate: $0.isDate, frame: $0.frame.offsetBy(dx: 0, dy: -listTop)) }

        guard dateHeight > 0,
              let top = rows.filter({ $0.frame.maxY > 0 }).min(by: { $0.frame.minY < $1.frame.minY }),
              let index = dayIds.firstIndex(of: top.dayId)
        else {
            return nil
        }

        if top.isDate, top.frame.minY >= 0 {
            // The day's date shows in its place, the day before's above it pushed up by it, if
            // there is one, and none at the top of the list.
            guard index > 0, let title = titles[dayIds[index - 1]] else {
                return nil
            }

            return Pinned(title: title, offset: min(0, top.frame.minY - dateHeight))
        }

        guard let title = titles[top.dayId] else {
            return nil
        }

        // Pushed up by the next day's date as it comes up under it.
        let nextDay = dayIds.indices.contains(index + 1) ? dayIds[index + 1] : nil
        let next = rows.first { $0.isDate && $0.dayId == nextDay }

        return Pinned(title: title, offset: next.map { min(0, $0.frame.minY - dateHeight) } ?? 0)
    }
}

extension ExpenseDay {
    /// What its date says, as the lists show it on its own.
    var title: String {
        date?.dayTitle(.standalone) ?? id
    }
}

extension View {
    /// The date of the day `day`, as a row of a list rather than a header, which iOS would pin and
    /// fade, telling `pinning` where it is, see `DayPinning`.
    func dateRow(of day: ExpenseDay, in pinning: DayPinning) -> some View {
        placed(in: pinning, rowId: "date:" + day.id, dayId: day.id, isDate: true)
            // Below the card, as Android's, 4 pt and its 8 pt, and outside what is placed, so that the
            // date pinned is as high as its card and the next one pushes it as soon as it reaches it.
            .padding(.bottom, 12)
            // Across the whole list, as Android's, rather than within a row's insets. Each of
            // these outside `placed`, which a list's row otherwise takes no notice of.
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .selectionDisabled()
    }

    /// This row of a list, `rowId`, of the day `dayId`, its date or one of its expenses, telling
    /// `pinning` where it is.
    func placed(in pinning: DayPinning, rowId: String, dayId: String, isDate: Bool) -> some View {
        onGeometryChange(for: CGRect.self) { proxy in
            proxy.frame(in: .global)
        } action: { frame in
            pinning.place(rowId: rowId, dayId: dayId, isDate: isDate, frame: frame)
        }
        .onDisappear {
            pinning.remove(rowId: rowId)
        }
    }

    /// The date `pinning` says is at the top of this list, laid over it, the list telling `pinning`
    /// where its top is, and nothing of the list above its top once it is scrolled past it, see
    /// `ListTopMask`.
    func pinnedDayOverlay(_ pinning: DayPinning) -> some View {
        onGeometryChange(for: CGFloat.self) { proxy in
            proxy.frame(in: .global).minY
        } action: { top in
            pinning.placeList(top: top)
        }
            .overlay(alignment: .top) {
                PinnedDay(pinning: pinning)
            }
            .mask {
                ListTopMask(pinning: pinning)
            }
    }
}

/// What of a list shows. Once it is scrolled past its top, nothing above it, as Android's list ends
/// at its top bar: neither a date pushed up, which would go over what is above the list, nor an
/// expense scrolled past the pinned day, which iOS would let run under the bar, blurred and showing.
/// At its top, all of it: a list touching the top of the safe area runs under the bar, its large
/// title being part of what it scrolls on iOS 26, and its top follows the bar down as the title
/// grows. Under the tab bar, all of it, as any list.
private struct ListTopMask: View {
    let pinning: DayPinning

    var body: some View {
        Rectangle()
            .ignoresSafeArea(edges: pinning.isScrolledPastTop ? .bottom : .all)
    }
}

/// The date pinned at the top of a list, see `DayPinning`: the only view drawn again as it scrolls.
private struct PinnedDay: View {
    let pinning: DayPinning

    var body: some View {
        if let pinned = pinning.pinned {
            ExpenseDayHeader(title: pinned.title)
                // Opaque down to the card's middle, so that an expense gone under it never shows
                // above it again, and seen at its rounded lower corners, as behind any card.
                .background {
                    VStack(spacing: 0) {
                        AppColors.background
                        Color.clear
                    }
                }
                .offset(y: pinned.offset)
                // The day's own date is in the list, which VoiceOver reads.
                .accessibilityHidden(true)
        }
    }
}
