import AppColors
import AppFonts
import SwiftUI

/// The contacts listed under one letter, as Android's contact list groups them.
struct ContactSection: Identifiable, Equatable {
    /// The letter, or `ContactSection.other` for names that do not start with one.
    let id: String
    let contacts: [Contact]

    /// The section for names that do not start with a letter, listed last.
    static let other = "#"

    /// `contacts` under the first letter of their names without its accent, or `other`, sections in
    /// the order of their letters' code units with `other` last, and each one's contacts by name as
    /// the user's language orders them, as Android's `ContactListScreen` groups them.
    static func grouping(_ contacts: [Contact]) -> [ContactSection] {
        let grouped = Dictionary(grouping: contacts.enumerated(), by: { sectionKey(of: $0.element.fullName) })

        return grouped.keys
            .sorted { first, second in
                if (first == other) != (second == other) {
                    return second == other
                }

                return first.utf16.lexicographicallyPrecedes(second.utf16)
            }
            .map { key in
                let sorted = grouped[key, default: []].sorted { first, second in
                    let order = first.element.fullName.localizedCompare(second.element.fullName)

                    // Two of the same name keep the order they came in.
                    return order == .orderedSame ? first.offset < second.offset : order == .orderedAscending
                }

                return ContactSection(id: key, contacts: sorted.map(\.element))
            }
    }

    /// The letter `fullName` is listed under: the first letter of the name without its accent, É
    /// under E, or `other` for a name that is empty or starts with anything but a letter, as
    /// Android tells it from the name's first UTF-16 unit.
    static func sectionKey(of fullName: String) -> String {
        guard
            let first = fullName.unicodeScalars.first,
            first.value <= 0xFFFF,
            first.isLetter,
            let base = fullName.decomposedStringWithCanonicalMapping.unicodeScalars.first
        else {
            return other
        }

        return String(base).uppercased()
    }
}

extension Unicode.Scalar {
    /// Whether this is a letter, of any script, as Kotlin's `Char.isLetter` tells it.
    var isLetter: Bool {
        switch properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter:
            true
        default:
            false
        }
    }
}

extension Array {
    /// `self` as Android's `sortedByName` orders people: the user, `selfUuid`, first, then the
    /// others by name as the user's language orders them, people with no name last, and ties by
    /// UUID.
    func sortedByName(
        selfUuid: String?,
        uuidOf: (Element) -> String,
        nameOf: (Element) -> String?
    ) -> [Element] {
        sorted { first, second in
            let firstUuid = uuidOf(first)
            let secondUuid = uuidOf(second)

            if (firstUuid == selfUuid) != (secondUuid == selfUuid) {
                return firstUuid == selfUuid
            }

            switch (nameOf(first), nameOf(second)) {
            case let (firstName?, secondName?):
                let order = firstName.localizedCompare(secondName)

                if order != .orderedSame {
                    return order == .orderedAscending
                }
            case (.some, nil):
                return true
            case (nil, .some):
                return false
            case (nil, nil):
                break
            }

            return firstUuid < secondUuid
        }
    }
}

extension String {
    /// Whether `self` holds nothing but whitespace.
    var isBlank: Bool {
        allSatisfy(\.isWhitespace)
    }

    /// `self` as a username is checked and sent: lowercased, as every username is, and without the
    /// whitespace around it, so that one pasted with a stray space still goes through.
    var trimmedUsername: String {
        trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}

/// A task that is cancelled once replaced, or once its owner is gone: what follows a stream for a
/// view model, which it must not keep alive.
final class TaskHandle: @unchecked Sendable {
    private var task: Task<Void, Never>?

    func replace(with task: Task<Void, Never>?) {
        self.task?.cancel()
        self.task = task
    }

    deinit {
        task?.cancel()
    }
}

/// Follows `stream` on the main actor for as long as `owner` lives, handing each value to `apply`.
@MainActor
func follow<Owner: AnyObject, Element: Sendable>(
    _ stream: AsyncStream<Element>,
    owner: Owner,
    into handle: TaskHandle,
    _ apply: @escaping @MainActor (Owner, Element) -> Void
) {
    handle.replace(
        with: Task { [weak owner] in
            for await value in stream {
                guard let owner, !Task.isCancelled else {
                    return
                }

                apply(owner, value)
            }
        }
    )
}

/// A circle with a person's initial over a color of their own, the same on every device, as
/// Android's `ProfilePictureCard` draws it.
struct ContactAvatar: View {
    let userUuid: String
    let name: String
    var size: CGFloat = 36

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

/// The full-width, prominent button that confirms a screen, with a spinner in place of its title
/// while what it started is under way, as the authentication screens have it.
struct ConfirmButton: View {
    private let title: Text
    private let isLoading: Bool
    private let action: () -> Void

    init(_ title: Text, isLoading: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.isLoading = isLoading
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            title
                // Kept in place while hidden, for the button to keep its size.
                .opacity(isLoading ? 0 : 1)
                .overlay {
                    if isLoading {
                        ProgressView()
                    }
                }
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.appProminent)
        .controlSize(.large)
        .disabled(isLoading)
    }
}

extension View {
    /// Pins `content` to the bottom of the screen, above the keyboard while it is up, with what
    /// scrolls behind it fading out under it as the system's own bars do.
    func bottomBar(@ViewBuilder content: () -> some View) -> some View {
        modifier(BottomBar(content: content()))
    }
}

private struct BottomBar<BarContent: View>: ViewModifier {
    let content: BarContent

    func body(content screen: Content) -> some View {
        if #available(iOS 26, *) {
            screen.safeAreaBar(edge: .bottom) {
                bar
            }
        } else {
            screen.safeAreaInset(edge: .bottom) {
                bar.background(AppColors.background)
            }
        }
    }

    private var bar: some View {
        VStack(spacing: 12) {
            content
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 12)
    }
}
