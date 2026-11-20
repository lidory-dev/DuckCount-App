import SwiftUI
import UIKit

/// What a screen shows in place of a spinner while it reads what it is about to show: the shape of
/// that content, its own list or form laid out with bones, as Android's `Skeleton` draws it. A
/// skeleton is a view of the screen's own, with `skeleton(_:)` set on it, which fades it in, pulses
/// it and makes it one element read out as loading; its bones are the views below.
public enum Skeleton {
    /// A bone's colour: of the text it stands for, faint enough to read as empty on the background
    /// and on a card alike, in the light scheme and the dark one.
    public static var boneColor: Color {
        AppColors.onSurface.opacity(boneOpacity)
    }

    /// How wide the `index`th line of a list is, of the room it has: lines of a few lengths, in an
    /// order that does not repeat for a screenful, read as text where lines of one length read as
    /// stripes. Android's, in the same order.
    public static func lineWidth(_ index: Int) -> CGFloat {
        lineWidths[index % lineWidths.count]
    }

    private static let lineWidths: [CGFloat] = [0.62, 0.45, 0.78, 0.54, 0.7, 0.4, 0.58]

    static let boneOpacity = 0.14

    /// How tall a text bone is, of its font's size: about that of a line's capital letters.
    static let letterHeight: CGFloat = 0.72

    /// How long a skeleton takes to fade in.
    static let appearanceDuration = 0.3

    /// How long each half of a pulse lasts, from full to faint or back.
    static let pulseDuration = 0.9

    /// How faint a skeleton is at the lowest of a pulse.
    static let faintestOpacity = 0.55
}

public extension View {
    /// This view as a skeleton, see `Skeleton`: faded in rather than shown at once, so that a read
    /// done in a frame or two, which most are, shows next to nothing of it, then pulsing, all of it
    /// together, and holding still under Reduce Motion. It takes no touches, which reach whatever is
    /// under it, a list it is laid over being pulled to refresh, and does not scroll, its list or
    /// scroll view laying it out from the top and clipping what goes past the screen, so it can
    /// have more rows than any phone shows. VoiceOver reads it as one element, `label`, the
    /// package's own "Loading", or not at all without one, for a part of a skeleton read out
    /// elsewhere.
    func skeleton(_ label: Text?) -> some View {
        modifier(SkeletonEffect(label: label))
    }

    /// This view on a card of a skeleton, in the colour of Material's `Card`, which the app's cards
    /// and inset rows are on, with the corners of an expense's card unless told otherwise.
    func skeletonCard(cornerRadius: CGFloat = 12) -> some View {
        background(AppColors.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
    }
}

/// See `skeleton(_:)`.
private struct SkeletonEffect: ViewModifier {
    let label: Text?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var isShown = false
    @State private var isFaint = false

    func body(content: Content) -> some View {
        content
            .scrollDisabled(true)
            .allowsHitTesting(false)
            // Each animation scoped to its own opacity, so that neither reaches the layout, a pulse
            // repeating forever otherwise moving whatever else changed with it.
            .animation(pulse) {
                $0.opacity(isFaint ? Skeleton.faintestOpacity : 1)
            }
            .animation(.easeOut(duration: Skeleton.appearanceDuration)) {
                $0.opacity(isShown ? 1 : 0)
            }
            .modifier(SkeletonAccessibility(label: label))
            .onAppear {
                isShown = true
                isFaint = !reduceMotion
            }
            .onChange(of: reduceMotion) { _, reduceMotion in
                isFaint = !reduceMotion
            }
    }

    /// Forever, or, with Reduce Motion turned on meanwhile, once back to full.
    private var pulse: Animation {
        reduceMotion
            ? .easeOut(duration: Skeleton.appearanceDuration)
            : .easeInOut(duration: Skeleton.pulseDuration).repeatForever(autoreverses: true)
    }
}

/// One element read out as `label`, or nothing at all without one.
private struct SkeletonAccessibility: ViewModifier {
    let label: Text?

    func body(content: Content) -> some View {
        if let label {
            content
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(label)
        } else {
            content.accessibilityHidden(true)
        }
    }
}

/// Where something will be, a rounded block in `Skeleton.boneColor`, sized by its frame.
public struct Bone: View {
    private let cornerRadius: CGFloat

    public init(cornerRadius: CGFloat = 4) {
        self.cornerRadius = cornerRadius
    }

    public var body: some View {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(Skeleton.boneColor)
    }
}

/// A line of text in `font`, the font of the text style `style`: as tall as the line, Dynamic Type
/// included, so that what is below it falls where the text's would, with a bone as tall as its
/// letters, `width` of the line's width and on its `alignment` side. It takes the whole width it is
/// offered, so it is best given a row of its own, or a fixed frame, rather than a spacer to share
/// it with.
public struct TextBone: View {
    private let font: Font
    private let style: Font.TextStyle
    private let width: CGFloat
    private let alignment: HorizontalAlignment

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    public init(font: Font, style: Font.TextStyle, width: CGFloat = 1, alignment: HorizontalAlignment = .leading) {
        self.font = font
        self.style = style
        self.width = width
        self.alignment = alignment
    }

    public var body: some View {
        // A line of the text itself, hidden, which is as tall as the font makes it.
        Text(verbatim: "X")
            .font(font)
            .lineLimit(1)
            .hidden()
            .frame(maxWidth: .infinity)
            .overlay {
                GeometryReader { proxy in
                    Bone()
                        .frame(width: proxy.size.width * width, height: letterHeight)
                        .frame(
                            width: proxy.size.width,
                            height: proxy.size.height,
                            alignment: Alignment(horizontal: alignment, vertical: .center)
                        )
                }
            }
            .accessibilityHidden(true)
    }

    /// Of the size `style` is at the user's text size, which `Font.app` sets its fonts at too.
    private var letterHeight: CGFloat {
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(dynamicTypeSize))

        return UIFont.preferredFont(forTextStyle: style.uiTextStyle, compatibleWith: traits).pointSize
            * Skeleton.letterHeight
    }
}

/// A person's picture, `size` across, as the app draws their initial in a circle.
public struct AvatarBone: View {
    private let size: CGFloat

    public init(size: CGFloat = 36) {
        self.size = size
    }

    public var body: some View {
        Circle()
            .fill(Skeleton.boneColor)
            .frame(width: size, height: size)
    }
}

/// A person, as the app's rows list them: their picture, `avatarSize` across, and their name,
/// 12 pt apart.
public struct PersonBone: View {
    private let avatarSize: CGFloat
    private let name: TextBone

    public init(avatarSize: CGFloat = 36, name: TextBone) {
        self.avatarSize = avatarSize
        self.name = name
    }

    public var body: some View {
        HStack(spacing: 12) {
            AvatarBone(size: avatarSize)

            name
        }
    }
}

/// A bordered button, `height` tall, as wide as it is offered: a capsule from iOS 26, as iOS draws
/// its buttons there, and a rounded rectangle before. `.large` buttons are about 50 pt tall, regular
/// ones about 34.
public struct ButtonBone: View {
    private let height: CGFloat

    public init(height: CGFloat = 50) {
        self.height = height
    }

    public var body: some View {
        shape
            .frame(maxWidth: .infinity)
            .frame(height: height)
    }

    @ViewBuilder
    private var shape: some View {
        if #available(iOS 26, *) {
            Capsule().fill(Skeleton.boneColor)
        } else {
            RoundedRectangle(cornerRadius: min(12, height / 4), style: .continuous).fill(Skeleton.boneColor)
        }
    }
}

private extension Font.TextStyle {
    var uiTextStyle: UIFont.TextStyle {
        switch self {
        case .largeTitle: .largeTitle
        case .title: .title1
        case .title2: .title2
        case .title3: .title3
        case .headline: .headline
        case .subheadline: .subheadline
        case .callout: .callout
        case .footnote: .footnote
        case .caption: .caption1
        case .caption2: .caption2
        default: .body
        }
    }
}
