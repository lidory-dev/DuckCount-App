import CryptoKit
import Foundation

/// The keys a push's payload is encrypted to, the server encrypting it as Web Push does (RFC 8291),
/// so that Apple, which carries it, reads nothing of who sent what.
///
/// Made on this device and never leaving it but for the public half, which the server is given with
/// the APNs token, see the shared module's `PushRepository`. One pair per session: made as the app
/// first registers for pushes, forgotten as the session ends.
public struct PushKeys: Sendable {
    /// The private half of this device's P-256 key.
    public let privateKey: P256.KeyAgreement.PrivateKey

    /// The 16 random bytes RFC 8291 mixes in, which the server holds alongside the public key.
    public let authSecret: Data

    public init(privateKey: P256.KeyAgreement.PrivateKey, authSecret: Data) {
        self.privateKey = privateKey
        self.authSecret = authSecret
    }

    /// A new pair, and a new secret.
    public static func generate() -> PushKeys {
        PushKeys(
            privateKey: P256.KeyAgreement.PrivateKey(),
            authSecret: SymmetricKey(size: .bits128).withUnsafeBytes { Data($0) }
        )
    }

    /// The public key, as an uncompressed point in base64url without padding: the subscription's
    /// `p256dh`, as Web Push calls it.
    public var p256dh: String {
        privateKey.publicKey.x963Representation.base64URLEncoded()
    }

    /// The secret, in base64url without padding: the subscription's `auth`.
    public var auth: String {
        authSecret.base64URLEncoded()
    }
}

/// Keeps this device's `PushKeys` where the extension can read them.
public struct PushKeyStore: Sendable {
    private static let account = "pushKeys"

    private let store: SecretStore

    public init(store: SecretStore = KeychainStore.shared) {
        self.store = store
    }

    /// The keys kept, or nil when there are none, or they cannot be read.
    public func load() -> PushKeys? {
        guard
            let data = store.read(Self.account),
            let kept = try? JSONDecoder().decode(Kept.self, from: data),
            let privateKey = try? P256.KeyAgreement.PrivateKey(rawRepresentation: kept.privateKey),
            kept.authSecret.count == 16
        else {
            return nil
        }

        return PushKeys(privateKey: privateKey, authSecret: kept.authSecret)
    }

    /// The keys kept, made and kept first when there are none. Nil only when new ones could not be
    /// kept: handed to the server, they would have it encrypt to keys nothing can read.
    public func loadOrCreate() -> PushKeys? {
        if let keys = load() {
            return keys
        }

        let keys = PushKeys.generate()

        guard
            let data = try? JSONEncoder().encode(
                Kept(privateKey: keys.privateKey.rawRepresentation, authSecret: keys.authSecret)
            ),
            store.write(data, to: Self.account)
        else {
            return nil
        }

        return keys
    }

    /// Forgets the keys, as the session ends: a push still on its way can no longer be read.
    public func delete() {
        store.delete(Self.account)
    }

    private struct Kept: Codable {
        let privateKey: Data
        let authSecret: Data
    }
}

extension Data {
    /// Base64url without padding, as RFC 8291 and the server write it.
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Reads base64url, with or without padding, or nil when `string` is not.
    init?(base64URLEncoded string: String) {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")

        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)

        self.init(base64Encoded: base64)
    }
}
