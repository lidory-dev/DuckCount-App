import UIKit
import UserNotifications

/// Where iOS hands the app what push is about: the device's token, a push that woke the app, and a
/// notification shown or tapped, none of which SwiftUI's scenes receive.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Before the launch ends, or a tap that launched the app would reach no delegate.
        UNUserNotificationCenter.current().delegate = self

        Notifier.shared.registerCategories()

        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushSetUp.tokenReceived(deviceToken)
    }

    /// Nothing to do: the next launch asks again.
    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("AppDelegate: could not register for pushes: \(error)")
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        Task { @MainActor in
            completionHandler(await PushWake.handle(userInfo))
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        await Notifier.shared.presentation(for: notification)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await Notifier.shared.open(response)
    }
}
