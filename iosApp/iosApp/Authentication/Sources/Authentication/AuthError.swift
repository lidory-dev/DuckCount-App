import Foundation

/// User-facing categories of authentication failure.
public enum AuthError: Error, Hashable, Sendable {
    case noInternet
    case unreachable
    case usernameTaken
    case invalidCredentials
    case timeout
    case serverError
    case cryptoError

    /// The full name is longer than the server takes, which the form keeps it from being.
    case fullNameTooLong

    /// The server limits logins and registrations per IP address, which can be shared.
    case tooManyAttempts

    /// The server refused the device's attestation, so no account can be created on it, which is
    /// a screen of its own rather than an error on the form, see `DeviceNotAllowedView`.
    case deviceNotAllowed

    /// App Attest could not attest the device, as trying again may change.
    case deviceCheckFailed

    case unknown

    /// What the form says about it, localized.
    var message: String {
        switch self {
        case .noInternet:
            String(localized: "No internet connection. Please check your network and try again.", bundle: .module)
        case .unreachable:
            String(localized: "Couldn’t reach the server. Please try again later.", bundle: .module)
        case .usernameTaken:
            String(localized: "This username is already taken.", bundle: .module)
        case .invalidCredentials:
            String(localized: "Incorrect username or password.", bundle: .module)
        case .timeout:
            String(localized: "The request timed out. Please try again.", bundle: .module)
        case .serverError:
            String(localized: "Something went wrong on our end. Please try again later.", bundle: .module)
        case .cryptoError:
            String(localized: "We couldn’t complete the secure handshake. Please try again.", bundle: .module)
        case .fullNameTooLong:
            String(localized: "Your full name is too long.", bundle: .module)
        case .tooManyAttempts:
            String(localized: "Too many attempts. Please try again in a few minutes.", bundle: .module)
        case .deviceNotAllowed:
            String(
                localized: "For security and abuse protection reasons, DuckCount accounts can only be created on a genuine iPhone or iPad. If yours is one, restart it and try again.",
                bundle: .module
            )
        case .deviceCheckFailed:
            String(localized: "This device couldn’t be verified. Please try again.", bundle: .module)
        case .unknown:
            String(localized: "Something went wrong. Please try again.", bundle: .module)
        }
    }
}
