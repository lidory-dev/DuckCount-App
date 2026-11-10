import SwiftUI

/// The whole authentication flow, onboarding, then logging in, or registering and setting up the
/// profile, with its own navigation, as Android's `AuthenticationFlow` is. Calls `onAuthenticated`
/// once the user is, for the app to leave the flow.
///
/// With `resume` set, opens straight on the profile step, to finish a registration the app was
/// closed in the middle of.
public struct AuthenticationFlowView: View {
    private let callBacks: any AuthenticationCallBacks
    private let resume: PendingProfile?
    private let onAuthenticated: @MainActor () -> Void

    @State private var path: [Route] = []

    private enum Route: Hashable {
        case login
        case register
        case deviceNotAllowed
        case setUpProfile(PendingProfile)
    }

    public init(
        callBacks: any AuthenticationCallBacks,
        resume: PendingProfile? = nil,
        onAuthenticated: @escaping @MainActor () -> Void
    ) {
        self.callBacks = callBacks
        self.resume = resume
        self.onAuthenticated = onAuthenticated
    }

    public var body: some View {
        NavigationStack(path: $path) {
            Group {
                if let resume {
                    SetUpProfileView(profile: resume, callBacks: callBacks, onSaved: onAuthenticated)
                } else {
                    OnBoardingView(
                        onRegister: { path.append(.register) },
                        onLogIn: { path.append(.login) }
                    )
                }
            }
            .navigationDestination(for: Route.self, destination: destination)
        }
    }

    @ViewBuilder
    private func destination(_ route: Route) -> some View {
        switch route {
        case .login:
            LoginView(callBacks: callBacks, onLoggedIn: onAuthenticated)

        case .register:
            RegisterView(
                callBacks: callBacks,
                // Over the form, with no way back to it: the account exists.
                onRegistered: { path.append(.setUpProfile($0)) },
                // In place of the form, so that going back leads to the onboarding screen, which
                // still offers to log in.
                onDeviceNotAllowed: { path = [.deviceNotAllowed] }
            )

        case .deviceNotAllowed:
            DeviceNotAllowedView()

        case .setUpProfile(let profile):
            SetUpProfileView(profile: profile, callBacks: callBacks, onSaved: onAuthenticated)
        }
    }
}

#Preview {
    AuthenticationFlowView(callBacks: PreviewAuthenticationCallBacks(), onAuthenticated: {})
}
