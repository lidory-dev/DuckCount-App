import SwiftUI

/// Where a registration ends once the server has refused the device's attestation. Nothing on the
/// form would change that answer, so the form is gone by now and going back leads to the
/// onboarding screen, which still offers to log in, logging in taking no attestation.
///
/// Laid out like the app's `UpdateRequiredScreen`, as Android lays the two out alike.
struct DeviceNotAllowedView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "iphone.slash")
                    .font(.system(size: 64))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)

                Text("Device not eligible", bundle: .module)
                    .font(.title2)
                    .fontWeight(.semibold)

                Text(AuthError.deviceNotAllowed.message)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding()
        }
        // Centered while it all fits. Past that, at a large Dynamic Type, it starts from the top
        // and scrolls, so that nothing is out of reach.
        .defaultScrollAnchor(.center, for: .alignment)
        .scrollBounceBehavior(.basedOnSize)
    }
}

#Preview {
    NavigationStack {
        DeviceNotAllowedView()
    }
}
