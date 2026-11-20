import SwiftUI
import UIKit

/// The launch screen once the app runs: `LaunchScreen.storyboard` itself, so that nothing moves when
/// iOS hands the screen over to the app. Kept up while the app finds where it starts, and for at
/// least a second from launch, as Android holds its splash, so that the sign-off can be read
/// (`StartupViewModel.isLaunchScreenShown`).
///
/// The storyboard draws Android's splash with Android's measures, the sign-off without its "by" or
/// "par": iOS draws the launch screen in a daemon of its own, which picks a storyboard's English copy
/// and an asset catalog's English picture whatever the language, so a word would change language
/// the moment the app takes over.
struct LaunchScreenView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        UIStoryboard(name: "LaunchScreen", bundle: nil).instantiateInitialViewController() ?? UIViewController()
    }

    func updateUIViewController(_ viewController: UIViewController, context: Context) {}
}

#Preview {
    LaunchScreenView()
        .ignoresSafeArea()
}
