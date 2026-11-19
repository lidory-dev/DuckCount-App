import AppFonts
import Shared
import SwiftUI

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    @ObservedObject private var session = AppSession.shared

    init() {
        // Before anything is drawn, the navigation bar's titles included, see AppFonts.
        AppFonts.styleSystemControls()

        KoinInit_iosKt.doInitKoin(
            sharedWakeList: AppWakeList(),
            syncAnnouncer: Notifier.shared,
            catchUpScheduler: BackgroundCatchUp.shared
        )

        AppSession.shared.followSessionEnd()
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if session.isRestarting {
                    // Every screen down while the session's leftovers go, nothing to show meanwhile.
                    Color(.systemBackground)
                        .ignoresSafeArea()
                } else {
                    // Built anew each time the app starts over, see AppSession.
                    ContentView()
                        .id(session.generation)
                }
            }
            // ABeeZee for whatever text sets no font of its own, as Android's body text is.
            .font(.app(.body))
            .alert(
                session.notice?.message ?? Text(verbatim: ""),
                isPresented: Binding(
                    get: { session.notice != nil },
                    set: { isPresented in
                        if !isPresented {
                            session.notice = nil
                        }
                    }
                )
            ) {
                Button(role: .cancel) {
                    session.notice = nil
                } label: {
                    Text("OK")
                }
            }
        }
        // What a push or a send held back left for later, see BackgroundCatchUp.
        .backgroundTask(.appRefresh(BackgroundCatchUp.identifier)) {
            await BackgroundCatchUp.shared.run()
        }
    }
}
