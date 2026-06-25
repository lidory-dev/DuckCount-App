import SwiftUI

public struct ContactColors: Equatable, Sendable {
    public let container: Color
    public let content: Color

    public init(container: Color, content: Color) {
        self.container = container
        self.content = content
    }
}

// Port of feature/contactList/.../util/ColorUtils.kt.
// The Android light and dark palettes are currently identical, so a single
// palette is used here. Colors are the same hex values as on Android.
private let contactColorPalette: [ContactColors] = [
    ContactColors(container: color(0x775891), content: color(0xF1DBFF)),
    ContactColors(container: color(0xECAD27), content: color(0x614300)),
    ContactColors(container: color(0xDE853F), content: color(0x532700)),
    ContactColors(container: color(0x2CA4D1), content: color(0x003548)),
    ContactColors(container: color(0xEA8885), content: color(0x682223)),
    ContactColors(container: color(0xB8A5EE), content: color(0x493878)),
]

public func colorsForContact(uuid: String) -> ContactColors {
    // `abs(Int(...))` widens the 32-bit hash to 64 bits first so Int32.min
    // cannot trap, then maps deterministically onto the palette, matching
    // Android's `hash.absoluteValue % colors.size`.
    let index = abs(Int(javaStringHashCode(uuid))) % contactColorPalette.count
    return contactColorPalette[index]
}

// Reimplements Java's `String.hashCode` (`h = 31 * h + char`, with Int32
// overflow over UTF-16 code units). Swift's own `hashValue` is randomized per
// process launch, which would make a contact's avatar color change on every
// run; this deterministic hash keeps it stable and matches Android.
private func javaStringHashCode(_ string: String) -> Int32 {
    var hash: Int32 = 0
    for unit in string.utf16 {
        hash = 31 &* hash &+ Int32(unit)
    }
    return hash
}

private func color(_ rgb: UInt32) -> Color {
    Color(
        red: Double((rgb >> 16) & 0xFF) / 255.0,
        green: Double((rgb >> 8) & 0xFF) / 255.0,
        blue: Double(rgb & 0xFF) / 255.0
    )
}
