import Shared
import SwiftUI

@main
struct iOSApp: App {
    @ObservedObject private var session = AppSession.shared

    init() {
        KoinInit_iosKt.doInitKoin()

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
    }
}
