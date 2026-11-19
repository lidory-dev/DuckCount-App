import AppFonts
import SwiftUI

extension String {
    /// Whether `self` holds nothing but whitespace.
    var isBlank: Bool {
        allSatisfy(\.isWhitespace)
    }

    /// `self` without the whitespace around it, as a name is checked and saved.
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// `self` as a username is checked and reserved: lowercased, as every username is, and without
    /// the whitespace around it, so that one pasted with a stray space still goes through.
    var trimmedUsername: String {
        trimmed.lowercased()
    }

    /// How long `self` is as every text limit of the app counts it: in UTF-16 units, as Kotlin's
    /// `String.length` does, which the server's limits are written in.
    var limitLength: Int {
        utf16.count
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

/// The full-width, prominent button that confirms a screen, with a spinner in place of its title
/// while what it started is under way, as the other screens have it.
struct ConfirmButton: View {
    private let title: Text
    private let isLoading: Bool
    private let role: ButtonRole?
    private let action: () -> Void

    init(_ title: Text, role: ButtonRole? = nil, isLoading: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.role = role
        self.isLoading = isLoading
        self.action = action
    }

    var body: some View {
        Button(role: role, action: action) {
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
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .tint(role == .destructive ? .red : nil)
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
                bar.background(.bar)
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

/// What a form says to begin with, under an icon.
struct Disclaimer<Content: View>: View {
    let systemImage: String
    var tint: Color?
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.largeTitle)
                .foregroundStyle(tint.map(AnyShapeStyle.init) ?? AnyShapeStyle(.tint))
                .accessibilityHidden(true)

            content()
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

/// What a form says under a field: why it cannot go on, in red, or what it just did.
struct FormMessage: View {
    private let text: String
    private let isError: Bool

    init(_ text: String, isError: Bool = true) {
        self.text = text
        self.isError = isError
    }

    var body: some View {
        if isError {
            Text(text)
                .foregroundStyle(.red)
        } else {
            Label {
                Text(text)
            } icon: {
                Image(systemName: "checkmark.circle.fill")
            }
            .foregroundStyle(.green)
        }
    }
}

/// The messages a form says under a section, one per line, the ones that apply.
struct FormMessages: View {
    private let messages: [String]

    init(_ messages: String?...) {
        self.messages = messages.compactMap { $0 }
    }

    var body: some View {
        if !messages.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(messages, id: \.self) { message in
                    FormMessage(message)
                }
            }
        }
    }
}

/// A circle with a person's initial over a color of their own, the same on every device and the
/// same as their contacts' apps draw for them, as Android's `ProfilePictureCard` draws it.
struct InitialAvatar: View {
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
