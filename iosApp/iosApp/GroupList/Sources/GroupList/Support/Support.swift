import SwiftUI

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

    /// `self` without the whitespace around it, as a field's text is checked, counted, compared and
    /// saved: the field itself keeps it as typed, or a space typed after a word would be swallowed.
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
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

/// The names the device holds for the people a screen shows, as they are read.
struct MemberNames: Equatable, Sendable {
    /// By UUID, for the people the device holds a name for.
    var known: [String: String] = [:]

    /// The people whose name was looked up, held or not.
    var read: Set<String> = []

    /// The name of `userUuid`, or nil while it is not read, or when the device holds none.
    func name(of userUuid: String) -> String? {
        known[userUuid]
    }

    /// What to call `userUuid`: their name, or "Someone else" once there is none to read, the
    /// device holding no profile for them, and nothing while it is still being read.
    func displayName(of userUuid: String) -> String {
        if let name = known[userUuid] {
            return name
        }

        return read.contains(userUuid) ? MemberNames.someoneElse : ""
    }

    /// What stands in for the name of somebody the device holds none for, as Android's
    /// `unknown_member`: a UUID says nothing to the person reading it.
    static var someoneElse: String {
        String(localized: "Someone else", bundle: .module)
    }
}

/// Follows the names of a set of people that changes as what a screen shows does, see
/// `GroupCallBacks.fullNames(of:)`, as Android's `displayNames` does: a member who is no contact of
/// the user's is named once a group sync reads their profile, which can be well after the screen
/// opened, so a name read once would never show.
@MainActor
final class NameFollower {
    private var followed: Set<String>?
    private let task = TaskHandle()
    private let onChange: @MainActor (MemberNames) -> Void

    init(onChange: @escaping @MainActor (MemberNames) -> Void) {
        self.onChange = onChange
    }

    /// Follows the names of `userUuids` from now on, through `callBacks`, rather than those of the
    /// people followed so far, whose names are kept until the new ones are read.
    func follow(_ userUuids: Set<String>, callBacks: any GroupCallBacks) {
        guard userUuids != followed else {
            return
        }

        followed = userUuids

        let names = callBacks.fullNames(of: userUuids)

        task.replace(
            with: Task { [weak self] in
                for await known in names {
                    guard let self, !Task.isCancelled else {
                        return
                    }

                    // An empty name says no more than none.
                    self.onChange(MemberNames(known: known.filter { !$0.value.isBlank }, read: userUuids))
                }
            }
        )
    }
}

/// A circle with a person's initial over a color of their own, the same on every device, as
/// Android's `ProfilePictureCard` draws it.
struct MemberAvatar: View {
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
                    .font(.system(size: size * 0.5, weight: .medium))
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
        AvatarColors(container: Color(rgb: 0x775891), content: Color(rgb: 0xF1DBFF)),
        AvatarColors(container: Color(rgb: 0xECAD27), content: Color(rgb: 0x614300)),
        AvatarColors(container: Color(rgb: 0xDE853F), content: Color(rgb: 0x532700)),
        AvatarColors(container: Color(rgb: 0x2CA4D1), content: Color(rgb: 0x003548)),
        AvatarColors(container: Color(rgb: 0xEA8885), content: Color(rgb: 0x682223)),
        AvatarColors(container: Color(rgb: 0xB8A5EE), content: Color(rgb: 0x493878)),
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
}

extension Color {
    /// The color `0xRRGGBB` stands for, as Android's palette writes them.
    init(rgb: UInt32) {
        self.init(
            red: Double((rgb >> 16) & 0xFF) / 255.0,
            green: Double((rgb >> 8) & 0xFF) / 255.0,
            blue: Double(rgb & 0xFF) / 255.0
        )
    }
}

/// The full-width, prominent button that confirms a screen, with a spinner in place of its title
/// while what it started is under way, as the other features' forms have it.
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
                .fontWeight(.semibold)
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
        .disabled(isLoading)
    }
}

/// A label whose icon gives way to a spinner while what it started is under way.
struct ProgressLabel<Title: View, Icon: View>: View {
    let isInProgress: Bool
    @ViewBuilder let title: () -> Title
    @ViewBuilder let icon: () -> Icon

    var body: some View {
        Label {
            title()
        } icon: {
            if isInProgress {
                ProgressView()
            } else {
                icon()
            }
        }
    }
}

/// What a group tells the user about itself in the brown and orange of Android's
/// `tertiaryContainer`, which the app keeps for notices: never a green, which would read as
/// something done.
struct NoticeCard: View {
    private let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Label {
            Text(text)
                .frame(maxWidth: .infinity, alignment: .leading)
        } icon: {
            Image(systemName: "info.circle.fill")
        }
        .font(.subheadline)
        .foregroundStyle(Color(rgb: 0xFFF5F1))
        .padding(12)
        .background(Color(rgb: 0xAE5B20), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Marks the member who created the group, and owns it for as long as it exists, as Android's
/// `OwnerLabel`.
struct OwnerLabel: View {
    var body: some View {
        // The system's grey rather than `.secondary`, which a row that is a button tints.
        Text("Owner", bundle: .module)
            .font(.caption.weight(.medium))
            .foregroundStyle(Color(uiColor: .secondaryLabel))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color(uiColor: .secondaryLabel).opacity(0.15), in: Capsule())
    }
}

/// A member of a group as the group's screens list them: their avatar and name, and whether they
/// own it.
struct MemberRow: View {
    let userUuid: String
    let name: String
    let isOwner: Bool

    var body: some View {
        HStack(spacing: 12) {
            MemberAvatar(userUuid: userUuid, name: name)

            Text(name)
                .foregroundStyle(Color.primary)
                .lineLimit(1)

            Spacer(minLength: 8)

            if isOwner {
                OwnerLabel()
            }
        }
        .accessibilityElement(children: .combine)
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
