import Foundation

/// Reads and writes the dates expenses are written with: ISO 8601 dates and times with no time zone,
/// as Kotlin's `LocalDateTime` writes them, "2026-11-10T14:05:30.123", read as they are, with no
/// conversion, as Android shows them.
enum LocalDateTime {
    private static let pattern = #"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$"#

    /// The date and time `string` says, in the device's calendar and time zone, or nil when it says
    /// none.
    static func date(from string: String) -> Date? {
        guard let match = string.wholeMatch(of: try! Regex(pattern)) else {
            return nil
        }

        func number(_ index: Int) -> Int? {
            match.output[index].substring.flatMap { Int($0) }
        }

        let components = DateComponents(
            calendar: .current,
            timeZone: .current,
            year: number(1),
            month: number(2),
            day: number(3),
            hour: number(4),
            minute: number(5),
            second: number(6) ?? 0
        )

        return components.date
    }

    /// The day `string` says, "2026-11-10", which a list groups expenses by.
    static func day(of string: String) -> String {
        String(string.prefix(10))
    }
}

extension Date {
    /// The day as a list of expenses titles it: "Monday 10 November", and its year when it is not
    /// this one.
    var dayTitle: String {
        formatted(isThisYear ? .dateTime.weekday(.wide).day().month(.wide) : .dateTime.weekday(.wide).day().month(.wide).year())
    }

    /// The day and time as an expense's history lists its versions by, the year too when it is not
    /// this one.
    var dayAndTimeTitle: String {
        let style: Date.FormatStyle = .dateTime.weekday(.wide).day().month(.wide).hour().minute()

        return formatted(isThisYear ? style : style.year())
    }

    /// The day and time as a navigation bar titles it, short enough to fit between its buttons:
    /// "10 Nov at 14:05", the year too when it is not this one.
    var shortDayAndTimeTitle: String {
        let style: Date.FormatStyle = .dateTime.day().month(.abbreviated).hour().minute()

        return formatted(isThisYear ? style : style.year())
    }

    private var isThisYear: Bool {
        Calendar.current.isDate(self, equalTo: .now, toGranularity: .year)
    }
}

extension Decimal {
    /// `self` written as an amount in `currency`, the way the user's language writes it: "€12.50",
    /// or "12,50 €" in French, with a sign in front when `withSign`.
    func formatted(in currency: Currency, withSign: Bool = false) -> String {
        let style = Decimal.FormatStyle.Currency(code: currency.code)
            .precision(.fractionLength(currency.hasSubunits ? 2 : 0))
            .sign(strategy: withSign ? .always() : .automatic)

        return formatted(style)
    }
}

extension String {
    /// `self`, an amount as the library writes it, written in `currency` the way the user's
    /// language writes it, or as it is next to the currency's symbol when it is no amount.
    func formattedAmount(in currency: Currency) -> String {
        guard let decimal = Decimal(string: self, locale: .posix) else {
            return "\(self) \(currency.symbol)"
        }

        return decimal.formatted(in: currency)
    }
}
