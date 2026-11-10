import SwiftUI

/// The full-width, prominent button that confirms a screen, with a spinner in place of its title
/// while what it started is under way.
struct ConfirmButton: View {
    private let title: LocalizedStringKey
    private let isLoading: Bool
    private let action: () -> Void

    init(_ title: LocalizedStringKey, isLoading: Bool = false, action: @escaping () -> Void) {
        self.title = title
        self.isLoading = isLoading
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title, bundle: .module)
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
