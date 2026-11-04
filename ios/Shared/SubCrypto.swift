import Foundation
import CryptoKit

/// Encrypted subscription links (`volta://crypt/<payload>`), the same format as on
/// Android: ECIES over P-256.
///
///     [0]      version, 0x01
///     [1]      key id
///     [2..66]  ephemeral P-256 public key, uncompressed
///     [67..78] AES-GCM nonce
///     [79..]   AES-256-GCM ciphertext ‖ 16-byte tag; AAD = bytes [0..66]
///
/// AES key = HKDF-SHA256(ECDH(app key, ephemeral key), salt = ephemeral key,
/// info = "volta-crypt-v1"). Like Happ's, the private key ships inside the app: this keeps
/// the address from being read or pasted elsewhere, not from someone taking the app apart.
enum SubCrypto {

    static let version: UInt8 = 0x01
    static let currentKeyId = 1
    private static let info = "volta-crypt-v1"
    private static let epkLength = 65
    private static let nonceLength = 12
    private static let tagLength = 16
    private static var headerLength: Int { 2 + epkLength }

    struct CryptError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// Opens a payload; throws CryptError with a message for the user.
    static func decrypt(_ payload: String) throws -> String {
        guard let bytes = decodeBase64Url(payload) else { throw CryptError(message: L10n.tr("link.crypt.damaged")) }
        guard bytes.count >= headerLength + nonceLength + tagLength + 1 else {
            throw CryptError(message: L10n.tr("link.crypt.cut"))
        }
        guard bytes[0] == version else { throw CryptError(message: L10n.tr("link.crypt.newer")) }
        guard Int(bytes[1]) == currentKeyId, let key = appKey else {
            throw CryptError(message: L10n.tr("link.crypt.unknownKey"))
        }
        let header = bytes.prefix(headerLength)
        let epk = bytes[2..<headerLength]
        let nonce = bytes[headerLength..<(headerLength + nonceLength)]
        let body = bytes[(headerLength + nonceLength)...]
        do {
            let ephemeral = try P256.KeyAgreement.PublicKey(x963Representation: epk)
            let shared = try key.sharedSecretFromKeyAgreement(with: ephemeral)
            let aes = shared.hkdfDerivedSymmetricKey(
                using: SHA256.self, salt: Data(epk), sharedInfo: Data(info.utf8), outputByteCount: 32
            )
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce),
                ciphertext: body.dropLast(tagLength),
                tag: body.suffix(tagLength)
            )
            let plain = try AES.GCM.open(box, using: aes, authenticating: header)
            return String(decoding: plain, as: UTF8.self)
        } catch {
            throw CryptError(message: L10n.tr("link.crypt.failed"))
        }
    }

    /// What a provider tool does (tools/volta_crypt.py), and how a backup keeps a hidden
    /// subscription address out of plain sight: the same format `decrypt` opens.
    static func encrypt(_ plaintext: String, publicKey: P256.KeyAgreement.PublicKey? = nil,
                        keyId: Int = currentKeyId) throws -> String {
        guard let recipient = publicKey ?? appKey?.publicKey else { throw CryptError(message: L10n.tr("link.crypt.unknownKey")) }
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let epk = ephemeral.publicKey.x963Representation
        var header = Data([version, UInt8(truncatingIfNeeded: keyId)])
        header.append(epk)
        let shared = try ephemeral.sharedSecretFromKeyAgreement(with: recipient)
        let aes = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self, salt: epk, sharedInfo: Data(info.utf8), outputByteCount: 32
        )
        let nonce = AES.GCM.Nonce()
        let box = try AES.GCM.seal(Data(plaintext.utf8), using: aes, nonce: nonce, authenticating: header)
        var out = header
        nonce.withUnsafeBytes { out.append(contentsOf: $0) }
        out.append(box.ciphertext)
        out.append(box.tag)
        return out.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingTrailing("=")
    }

    /// The app's key, masked so it is not a greppable string in the binary.
    private static let appKey: P256.KeyAgreement.PrivateKey? = {
        let parts = [
            "SydWXdj75T4nFImTpEUIHqfh4cbvzD37YxsnIiKyX2ShTDL",
            "yaHCMzvdn+Qn26JElVMWJB/5E+fZST2BBzFzUwsh4pguA7x",
            "b6hn8AdA22fm+d/SiYmICFhAlU7sz+LkLm7gu9/qlIoyxUz",
            "HAHmAwcBKJUkU7DzViCRUlIYFr3pDdlM36f+AlPly9G"
        ]
        guard let masked = Data(base64Encoded: parts.joined()) else { return nil }
        var mask = Data()
        var i: UInt8 = 0
        while mask.count < masked.count {
            var block = Data("volta-crypt-mask-v1".utf8)
            block.append(i)
            mask.append(contentsOf: SHA256.hash(data: block))
            i &+= 1
        }
        let der = Data(zip(masked, mask).map { $0 ^ $1 })
        return try? P256.KeyAgreement.PrivateKey(derRepresentation: der)
    }()

    private static func decodeBase64Url(_ text: String) -> Data? {
        let clean = text.trimmed
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .filter { !$0.isWhitespace }
            .trimmingTrailing("=")
        if clean.isEmpty { return nil }
        return Codec.decodeBase64Data(clean)
    }
}
