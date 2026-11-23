import Foundation

extension String {
    /// `self`, an amount as the library writes it, "-98.67", written in `currency` the way `locale`'s
    /// language writes it: "-€98.67", or "-98,67 €" in French, as the expense lists write theirs
    /// (ExpenseList's `formattedAmount(in:)`) and as Android writes every amount (`formatAmount`).
    /// For the screen alone: what is recorded, a settlement's amount included, stays the library's.
    /// Written as it is next to the currency's symbol when it is no amount.
    func formattedAmount(in currency: GroupCurrency, locale: Locale = .current) -> String {
        guard let decimal = Decimal(string: self, locale: Locale(identifier: "en_US_POSIX")) else {
            return "\(self) \(currency.symbol)"
        }

        let style = Decimal.FormatStyle.Currency(code: currency.code, locale: locale)
            .precision(.fractionLength(currency.hasSubunits ? 2 : 0))

        return decimal.formatted(style)
    }
}
