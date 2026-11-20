import CoreGraphics
import Testing

@testable import ExpenseList

/// Which day's date is pinned at the top of a list, and how far the next one pushes it, as Android's
/// `stickyHeader` pins them, see `DayPinning`. Dates 50 pt high, the list's top at 100 on the screen.
@MainActor
struct DayPinningTests {
    private let pinning = DayPinning()

    init() {
        pinning.setDays([("tue", "Tuesday"), ("mon", "Monday"), ("sun", "Sunday")])
        pinning.placeList(top: 100)
    }

    /// The rows of the list, each at `top` from the list's top, `height` high.
    private func place(_ rows: [(id: String, day: String, isDate: Bool, top: CGFloat, height: CGFloat)]) {
        for row in rows {
            pinning.place(
                rowId: row.id,
                dayId: row.day,
                isDate: row.isDate,
                frame: CGRect(x: 0, y: 100 + row.top, width: 400, height: row.height)
            )
        }
    }

    @Test func nothingIsPinnedAtTheTopOfTheList() {
        place([("date:tue", "tue", true, 0, 50), ("a", "tue", false, 70, 60), ("date:mon", "mon", true, 150, 50)])

        #expect(pinning.pinned == nil)
    }

    @Test func theDayAtTheTopIsPinnedOnceItsDateScrollsAway() {
        place([("date:tue", "tue", true, -20, 50), ("a", "tue", false, 50, 60), ("date:mon", "mon", true, 130, 50)])

        #expect(pinning.pinned == DayPinning.Pinned(title: "Tuesday", offset: 0))
    }

    @Test func theNextDaysDatePushesThePinnedOneUp() {
        place([("a", "tue", false, -40, 60), ("date:mon", "mon", true, 30, 50)])

        #expect(pinning.pinned == DayPinning.Pinned(title: "Tuesday", offset: -20))
    }

    /// Between the last expense of a day and the next day's date, with no row at the top itself.
    @Test func aDayStaysPinnedInTheGapBeforeTheNextDate() {
        place([("a", "tue", false, -70, 60), ("date:mon", "mon", true, 10, 50)])

        #expect(pinning.pinned == DayPinning.Pinned(title: "Tuesday", offset: -40))
    }

    @Test func theNextDayIsPinnedOnceItsDateReachesTheTop() {
        place([("a", "tue", false, -90, 60), ("date:mon", "mon", true, -5, 50), ("b", "mon", false, 65, 60)])

        #expect(pinning.pinned == DayPinning.Pinned(title: "Monday", offset: 0))
    }

    @Test func aRowGoneFromTheListIsForgotten() {
        place([("a", "tue", false, -40, 60), ("date:mon", "mon", true, 30, 50)])
        pinning.remove(rowId: "date:mon")

        #expect(pinning.pinned == DayPinning.Pinned(title: "Tuesday", offset: 0))
    }
}
