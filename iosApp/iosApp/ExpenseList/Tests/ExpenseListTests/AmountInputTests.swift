import Foundation
import Testing

@testable import ExpenseList

/// An amount field's text as typed, and the amount it stands for, as Android's `AmountInputTest`.
struct AmountInputTests {
    @Test(arguments: ["", "12", "12.", "12,", "12.5", "12,50", ".5", "007"])
    func digitsAndOneSeparatorWithUpToTwoDecimalsAreTakenAsTyped(text: String) {
        #expect(text.isAmountInput(hasSubunits: true))
    }

    /// Refused rather than rounded or cleaned, which would rewrite what is being typed.
    @Test(arguments: ["12.505", "12.5.5", "12,5,", "1a", "-3", "1 2", "١٢"])
    func aThirdDecimalASecondSeparatorOrAnythingButDigitsIsRefused(text: String) {
        #expect(!text.isAmountInput(hasSubunits: true))
    }

    @Test func aCurrencyWithoutSubunitsTakesNoSeparator() {
        #expect("1200".isAmountInput(hasSubunits: false))
        #expect(!"12.".isAmountInput(hasSubunits: false))
        #expect(!"12,".isAmountInput(hasSubunits: false))
    }

    @Test func theAmountATextStandsForIsWrittenAsTheFormKeepsIt() {
        #expect("12,5".toAmount(hasSubunits: true) == "12.50")
        #expect("12.".toAmount(hasSubunits: true) == "12.00")
        #expect(".5".toAmount(hasSubunits: true) == "0.50")
        #expect("12".toAmount(hasSubunits: true) == "12.00")
        #expect("007".toAmount(hasSubunits: true) == "7.00")
        #expect("1200".toAmount(hasSubunits: false) == "1200")
        #expect("123456789012345.99".toAmount(hasSubunits: true) == "123456789012345.99")
    }

    @Test func nothingOrAZeroAloneStandsForNoAmountYet() {
        #expect("".toAmount(hasSubunits: true) == "")
        #expect("0".toAmount(hasSubunits: true) == "")
    }

    @Test func roundingIsHalfUp() {
        #expect("120.50".rounded(scale: 0) == "121")
        #expect("2.005".rounded(scale: 2) == "2.01")
        #expect("10".rounded(scale: 2) == "10.00")
    }

    @Test func deletingTheSeparatorDropsTheDecimalsWithIt() {
        #expect("1250".droppingDecimalsIfSeparatorDeleted(previous: "12.50") == "12")
        #expect("125".droppingDecimalsIfSeparatorDeleted(previous: "12,5") == "12")
        #expect("12".droppingDecimalsIfSeparatorDeleted(previous: "12.") == "12")
    }

    @Test func anyOtherEditIsKeptAsItIs() {
        #expect("12.0".droppingDecimalsIfSeparatorDeleted(previous: "12.50") == "12.0")
        #expect("1250".droppingDecimalsIfSeparatorDeleted(previous: "125") == "1250")
        #expect("12.5".droppingDecimalsIfSeparatorDeleted(previous: "12.") == "12.5")
    }

    @Test func timestampsAreReadWithOrWithoutSecondsAndFractions() throws {
        let date = try #require(LocalDateTime.date(from: "2026-11-10T14:05:30"))
        let components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)

        #expect([components.year, components.month, components.day] == [2026, 11, 10])
        #expect([components.hour, components.minute, components.second] == [14, 5, 30])
        #expect(LocalDateTime.date(from: "2026-11-10T14:05") == LocalDateTime.date(from: "2026-11-10T14:05:00"))
        #expect(LocalDateTime.date(from: "2026-11-10T14:05:30.123456789") == date)
        #expect(LocalDateTime.date(from: "yesterday") == nil)
        #expect(LocalDateTime.day(of: "2026-11-10T14:05:30.123") == "2026-11-10")
    }
}
