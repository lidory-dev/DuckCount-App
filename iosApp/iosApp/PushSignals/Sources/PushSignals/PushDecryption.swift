import CryptoKit
import Foundation

/// Opens what the server encrypted to this device's `PushKeys`: one `aes128gcm` record (RFC 8188),
/// keyed as Web Push keys it (RFC 8291), the very body the server sends an Android device, carried
/// in the APNs payload instead of an HTTP body.
public enum PushDecryption {
    /// The plaintext of `body`, or nil when it is not a record encrypted to `keys`: whatever does not
    /// open did not come from the server, or was for a session this device has left.
    public static func decrypt(_ body: Data, keys: PushKeys) -> Data? {
        let body = Data(body)

        // The header: a 16-byte salt, a 4-byte record size, and the key id, which RFC 8291 makes the
        // sender's public key, 65 bytes.
        guard body.count > headerLength else {
            return nil
        }

        let salt = body.subdata(in: 0..<16)
        let recordSize = body.subdata(in: 16..<20).reduce(0) { $0 << 8 | UInt32($1) }
        let keyIdLength = Int(body[20])

        guard keyIdLength == publicKeyLength else {
            return nil
        }

        let senderPublicKey = body.subdata(in: 21..<headerLength)
        let record = body.subdata(in: headerLength..<body.count)

        // One record, the last: what the server sends is far shorter than a record may be, and a
        // record holds at least a tag and the delimiter that ends the padding.
        guard recordSize > tagLength + 1, record.count <= Int(recordSize), record.count > tagLength else {
            return nil
        }

        guard
            // Checks the point is on the curve, which RFC 8291 requires before agreeing on a secret.
            let sender = try? P256.KeyAgreement.PublicKey(x963Representation: senderPublicKey),
            let shared = try? keys.privateKey.sharedSecretFromKeyAgreement(with: sender)
        else {
            return nil
        }

        let keyInfo = Data("WebPush: info\0".utf8) + keys.privateKey.publicKey.x963Representation + senderPublicKey

        let inputKeyingMaterial = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: keys.authSecret,
            sharedInfo: keyInfo,
            outputByteCount: 32
        )

        let contentKey = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: inputKeyingMaterial,
            salt: salt,
            info: Data("Content-Encoding: aes128gcm\0".utf8),
            outputByteCount: 16
        )

        let nonce = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: inputKeyingMaterial,
            salt: salt,
            info: Data("Content-Encoding: nonce\0".utf8),
            outputByteCount: 12
        )

        guard
            let sealed = try? AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce.withUnsafeBytes { Data($0) }),
                ciphertext: record.prefix(record.count - tagLength),
                tag: record.suffix(tagLength)
            ),
            let padded = try? AES.GCM.open(sealed, using: contentKey)
        else {
            return nil
        }

        // The padding: zeros after the delimiter, which is 2 for the last record.
        guard let delimiter = padded.lastIndex(where: { $0 != 0 }), padded[delimiter] == 2 else {
            return nil
        }

        return padded.subdata(in: padded.startIndex..<delimiter)
    }

    /// The header's length with a P-256 public key for its key id.
    private static let headerLength = 16 + 4 + 1 + publicKeyLength

    /// An uncompressed P-256 point.
    private static let publicKeyLength = 65

    /// AES-GCM's tag.
    private static let tagLength = 16
}
