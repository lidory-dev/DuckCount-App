import Foundation

// The rules an amount field follows, as Android's `utils/StringUtils.kt` has them.

extension String {
    /// Whether `self`, an amount field's text as typed, is one the field takes: digits and, in a
    /// currency with subunits, one decimal separator, a dot or a comma as the keyboard has it,
    /// followed by at most two of them. Anything else is refused rather than rewritten: rewriting
    /// what is being typed would move the cursor.
    func isAmountInput(hasSubunits: Bool) -> Bool {
        let pattern = hasSubunits ? #"^[0-9]*([.,][0-9]{0,2})?$"# : #"^[0-9]*$"#

        return range(of: pattern, options: .regularExpression) != nil
    }

    /// The amount `self`, an amount field's text (see `isAmountInput`), stands for, as the form
    /// keeps and saves it, the one way the library takes an amount: "12" and "12.00" are 12.00 in a
    /// currency with subunits and "12" is 12 in one without, "12,5" is 12.50, "007" is 7.00, and "0"
    /// none yet.
    func toAmount(hasSubunits: Bool) -> String {
        let formatted = formattedAmountWithSubunits

        if formatted.isBlank {
            return formatted
        }

        return formatted.rounded(scale: hasSubunits ? 2 : 0)
    }

    /// `self`, an amount field's text right after an edit of `previous`, its decimals dropped along
    /// with the decimal separator when the edit deleted that alone: 12.50 without its dot is 12,
    /// never 1250.
    func droppingDecimalsIfSeparatorDeleted(previous: String) -> String {
        guard let separator = previous.firstIndex(where: { $0 == "." || $0 == "," }) else {
            return self
        }

        var withoutSeparator = previous
        withoutSeparator.remove(at: separator)

        return self == withoutSeparator ? String(previous[..<separator]) : self
    }

    /// `self` as a number the library reads: "0" for nothing, and a leading zero before a leading
    /// decimal point.
    var validNumber: String {
        if isBlank {
            return "0"
        }

        return hasPrefix(".") ? "0" + self : self
    }

    /// `self` written with `scale` decimals, rounded half up, or as it is when it is no number.
    func rounded(scale: Int) -> String {
        let trimmed = trimmingCharacters(in: .whitespaces)

        guard trimmed.range(of: #"^-?[0-9]*(\.[0-9]+)?$"#, options: .regularExpression) != nil else {
            return self
        }

        // Nothing to write an amount from.
        if trimmed.isEmpty || trimmed == "-" {
            return trimmed
        }

        guard var decimal = Decimal(string: trimmed, locale: .posix) else {
            return self
        }

        var rounded = Decimal()
        NSDecimalRound(&rounded, &decimal, scale, .plain)

        return rounded.plainString(scale: scale)
    }

    /// Whether `self` holds nothing but whitespace.
    var isBlank: Bool {
        allSatisfy(\.isWhitespace)
    }

    /// Digits and dots only, a comma taken for a dot, without leading zeros, and two or three
    /// decimals when it has any, for `rounded` to round the third.
    private var formattedAmountWithSubunits: String {
        let replaced = String(
            replacingOccurrences(of: ",", with: ".")
                .filter { $0.isASCII && ($0.isNumber || $0 == ".") }
                .drop { $0 == "0" }
        )

        guard let dot = replaced.firstIndex(of: ".") else {
            return replaced
        }

        let integerPart = replaced[..<dot]
        var fractionPart = String(replaced[replaced.index(after: dot)...].prefix(3))

        while fractionPart.count < 2 {
            fractionPart.append("0")
        }

        return "\(integerPart).\(fractionPart)"
    }
}

extension Decimal {
    /// Written with exactly `scale` decimals, with a dot and no grouping, as the library writes an
    /// amount.
    func plainString(scale: Int) -> String {
        let written = NSDecimalNumber(decimal: self).stringValue
        let parts = written.split(separator: ".", omittingEmptySubsequences: false)
        let integerPart = String(parts[0])

        guard scale > 0 else {
            return integerPart
        }

        var fractionPart = parts.count > 1 ? String(parts[1]) : ""

        while fractionPart.count < scale {
            fractionPart.append("0")
        }

        return "\(integerPart).\(fractionPart.prefix(scale))"
    }
}

extension Locale {
    /// The locale amounts are written in for the library and the server: a dot, and no grouping.
    static let posix = Locale(identifier: "en_US_POSIX")
}
