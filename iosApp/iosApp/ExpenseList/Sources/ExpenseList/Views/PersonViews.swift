import AppFonts
import SwiftUI

/// A circle with a person's initial over a color of their own, the same on every device, as
/// Android's `ProfilePictureCard` draws it.
struct PersonAvatar: View {
    let userUuid: String
    let name: String
    var size: CGFloat = 32

    var body: some View {
        let colors = AvatarColors.of(userUuid: userUuid)

        Circle()
            .fill(colors.container)
            .frame(width: size, height: size)
            .overlay {
                Text(name.prefix(1).uppercased())
                    .font(.app(size: size * 0.5))
                    .foregroundStyle(colors.content)
            }
            .accessibilityHidden(true)
    }
}

/// Somebody on an expense, and what they paid or owe of it.
struct PersonAmountRow: View {
    let userUuid: String
    let name: String
    let amount: String
    let currency: Currency

    var body: some View {
        HStack(spacing: 12) {
            PersonAvatar(userUuid: userUuid, name: name)

            Text(name)
                .lineLimit(1)

            Spacer(minLength: 8)

            Text(amount.formattedAmount(in: currency))
                .monospacedDigit()
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Says that the expense was marked as paid back, and on which day when `settledAt` is given.
struct SettledLabel: View {
    var settledAt: String?

    var body: some View {
        Label {
            if let date = settledAt.flatMap(LocalDateTime.date(from:)) {
                Text("Settled on \(date.dayTitle)", bundle: .module)
            } else {
                Text("Settled", bundle: .module)
            }
        } icon: {
            Image(systemName: "checkmark.seal.fill")
        }
        .foregroundStyle(.tint)
    }
}

/// The colors a person's avatar takes, by their UUID, from the palette Android's draw from.
private struct AvatarColors {
    let container: Color
    let content: Color

    private static let palette: [AvatarColors] = [
        AvatarColors(container: color(0x775891), content: color(0xF1DBFF)),
        AvatarColors(container: color(0xECAD27), content: color(0x614300)),
        AvatarColors(container: color(0xDE853F), content: color(0x532700)),
        AvatarColors(container: color(0x2CA4D1), content: color(0x003548)),
        AvatarColors(container: color(0xEA8885), content: color(0x682223)),
        AvatarColors(container: color(0xB8A5EE), content: color(0x493878)),
    ]

    /// As Android picks them, `hash.absoluteValue % colors.size` of Java's `String.hashCode`, which
    /// Swift's own hash, randomized per launch, could not stand in for.
    static func of(userUuid: String) -> AvatarColors {
        var hash: Int32 = 0

        for unit in userUuid.utf16 {
            hash = 31 &* hash &+ Int32(unit)
        }

        return palette[abs(Int(hash)) % palette.count]
    }

    private static func color(_ rgb: UInt32) -> Color {
        Color(
            red: Double((rgb >> 16) & 0xFF) / 255.0,
            green: Double((rgb >> 8) & 0xFF) / 255.0,
            blue: Double(rgb & 0xFF) / 255.0
        )
    }
}
