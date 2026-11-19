import CryptoKit
import Foundation
import Testing

@testable import PushSignals

/// What the server encrypts as Web Push does, opened as RFC 8291's own example says it opens.
struct PushDecryptionTests {
    /// RFC 8291, section 5: the user agent's keys and secret.
    private let receiver = PushKeys(
        privateKey: try! P256.KeyAgreement.PrivateKey(
            rawRepresentation: Data(base64URLEncoded: "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")!
        ),
        authSecret: Data(base64URLEncoded: "BTBZMqHH6r4Tts7J_aSIgg")!
    )

    /// RFC 8291, section 5: the message the application server sent.
    private let message = Data(
        base64URLEncoded: "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
            + "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
            + "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
    )!

    @Test func opensTheExampleOfRfc8291() {
        let plaintext = PushDecryption.decrypt(message, keys: receiver)

        #expect(plaintext.map { String(decoding: $0, as: UTF8.self) } == "When I grow up, I want to be a watermelon")
    }

    @Test func theKeysPublishedAreTheExamplesPublicKeyAndSecret() {
        #expect(
            receiver.p256dh
                == "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
        )
        #expect(receiver.auth == "BTBZMqHH6r4Tts7J_aSIgg")
    }

    @Test func doesNotOpenAMessageForOtherKeys() {
        #expect(PushDecryption.decrypt(message, keys: .generate()) == nil)
    }

    @Test func doesNotOpenAMessageUnderAnotherSecret() {
        let keys = PushKeys(privateKey: receiver.privateKey, authSecret: Data(repeating: 7, count: 16))

        #expect(PushDecryption.decrypt(message, keys: keys) == nil)
    }

    @Test(arguments: [0, 20, 30, 100, 140])
    func doesNotOpenAMessageChangedAnywhere(at index: Int) {
        var changed = message
        changed[index] ^= 1

        #expect(PushDecryption.decrypt(changed, keys: receiver) == nil)
    }

    @Test(arguments: [0, 20, 86, 100])
    func doesNotOpenAMessageCutShort(to length: Int) {
        #expect(PushDecryption.decrypt(message.prefix(length), keys: receiver) == nil)
    }

    /// The same message carried in an APNs payload, the server putting it under `e` in base64url.
    @Test func readsTheSignalAPushCarries() throws {
        let userInfo: [AnyHashable: Any] = [
            "aps": ["content-available": 1],
            "e": message.base64URLEncoded(),
        ]

        // The example's plaintext is no signal, so it reads as an unknown kind from nobody.
        #expect(PushPayload.signal(in: userInfo, keys: receiver) == PushSignal(kind: .unknown, from: nil))
    }

    @Test func readsNoSignalWithoutKeysOrPayload() {
        let userInfo: [AnyHashable: Any] = ["e": message.base64URLEncoded()]

        #expect(PushPayload.signal(in: userInfo, keys: nil) == nil)
        #expect(PushPayload.signal(in: ["aps": [:] as [String: Any]], keys: receiver) == nil)
        #expect(PushPayload.signal(in: ["e": "not base64url!"], keys: receiver) == nil)
    }
}
