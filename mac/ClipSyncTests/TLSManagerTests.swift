import XCTest
@testable import ClipSync

final class TLSManagerTests: XCTestCase {
    private var service: String = ""
    private var keychain: Keychain!

    override func setUp() {
        super.setUp()
        service = "com.clipsync.tests.tls.\(UUID().uuidString)"
        keychain = Keychain(service: service)
    }

    override func tearDown() {
        try? keychain.delete(account: "tls-cert-der")
        try? keychain.delete(account: "tls-key-pem")
        super.tearDown()
    }

    func testGenerateProducesUsableIdentity() throws {
        let identity = try TLSManager.generateSelfSigned(
            hostnames: ["localhost", "test.local"],
            ipAddresses: ["127.0.0.1"]
        )
        XCTAssertFalse(identity.certDER.isEmpty)
        XCTAssertTrue(identity.keyPEM.contains("BEGIN PRIVATE KEY") ||
                      identity.keyPEM.contains("BEGIN EC PRIVATE KEY"))

        let fp = try TLSManager.spkiFingerprint(certDER: identity.certDER)
        XCTAssertFalse(fp.isEmpty)
        XCTAssertFalse(fp.contains("="))
        XCTAssertFalse(fp.contains("+"))
        XCTAssertFalse(fp.contains("/"))
    }

    func testLoadOrCreateIsStableAcrossInstances() throws {
        let manager1 = TLSManager(keychain: keychain)
        do {
            try manager1.loadOrCreate()
        } catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("Keychain access unavailable (status=\(status))")
        }
        let fp1 = manager1.spkiFingerprint
        XCTAssertFalse(fp1.isEmpty)

        let manager2 = TLSManager(keychain: keychain)
        try manager2.loadOrCreate()
        XCTAssertEqual(manager1.spkiFingerprint, manager2.spkiFingerprint)
        XCTAssertEqual(manager1.certificateDER, manager2.certificateDER)
    }

    func testMakeServerTLSConfigurationDoesNotThrow() throws {
        let manager = TLSManager(keychain: keychain)
        do {
            try manager.loadOrCreate()
        } catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("Keychain access unavailable (status=\(status))")
        }
        XCTAssertNoThrow(try manager.makeServerTLSConfiguration())
    }

    func testBase64URLNoPadding() {
        let data = Data([0xFF, 0xEE, 0xDD, 0xCC])
        let encoded = TLSManager.base64URLNoPadding(data)
        XCTAssertEqual(encoded, "_-7dzA")
    }
}


final class TLSManagerFailureTests: XCTestCase {
    func testPartialIdentityIsNotReplaced() throws {
        for missingAccount in ["tls-cert-der", "tls-key-pem"] {
            let storage = TestKeychainStorage()
            storage.values[missingAccount == "tls-cert-der" ? "tls-key-pem" : "tls-cert-der"] = Data([1, 2, 3])
            let manager = TLSManager(keychain: storage)
            XCTAssertThrowsError(try manager.loadOrCreate())
            XCTAssertEqual(storage.saves, 0)
            XCTAssertTrue(manager.spkiFingerprint.isEmpty)
            XCTAssertThrowsError(try manager.makeServerTLSConfiguration())
        }
    }

    func testMismatchedPrivateKeyFailsBeforePublishingIdentity() throws {
        let a = try TLSManager.generateSelfSigned(hostnames: ["localhost"], ipAddresses: [])
        let b = try TLSManager.generateSelfSigned(hostnames: ["localhost"], ipAddresses: [])
        let storage = TestKeychainStorage()
        storage.values["tls-cert-der"] = a.certDER
        storage.values["tls-key-pem"] = Data(b.keyPEM.utf8)
        let manager = TLSManager(keychain: storage)
        XCTAssertThrowsError(try manager.loadOrCreate())
        XCTAssertTrue(manager.spkiFingerprint.isEmpty)
        XCTAssertEqual(storage.saves, 0)
    }

    func testStorageFailureCannotPublishNewIdentity() throws {
        let storage = TestKeychainStorage()
        storage.saveFailure = KeychainError.unexpectedStatus(-1)
        let manager = TLSManager(keychain: storage)
        XCTAssertThrowsError(try manager.loadOrCreate())
        XCTAssertTrue(manager.spkiFingerprint.isEmpty)
        XCTAssertThrowsError(try manager.makeServerTLSConfiguration())
    }

    func testInvalidEncodingIsNotTreatedAsMissingIdentity() throws {
        let storage = TestKeychainStorage()
        storage.values["tls-cert-der"] = Data([1])
        storage.values["tls-key-pem"] = Data([0xff])
        XCTAssertThrowsError(try TLSManager(keychain: storage).loadOrCreate())
        XCTAssertEqual(storage.saves, 0)
    }
}
