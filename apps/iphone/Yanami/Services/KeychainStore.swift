import Foundation
import LocalAuthentication
import Security

struct KeychainStore {
    let service: String

    func read<T: Decodable>(_ type: T.Type, account: String) -> T? {
        try? readStrict(type, account: account)
    }

    /// Reads a Keychain value without collapsing access and decoding failures into "not found".
    /// Security bootstrap callers use this to fail closed when protected state is unavailable.
    func readStrict<T: Decodable>(_ type: T.Type, account: String) throws -> T? {
        guard let data = try readDataStrict(account: account) else { return nil }
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw KeychainError.decoding(error)
        }
    }

    /// Reads raw data while explicitly refusing implicit Keychain authentication UI. Callers
    /// accessing an ACL-protected item must provide an already-evaluated `LAContext`.
    func readDataStrict(
        account: String,
        authenticationContext: LAContext? = nil
    ) throws -> Data? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        let context = authenticationContext ?? LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else {
            throw KeychainError.unhandled(status)
        }
        guard let data = item as? Data else {
            throw KeychainError.invalidData
        }
        return data
    }

    func save<T: Encodable>(_ value: T, account: String) throws {
        let data = try JSONEncoder().encode(value)
        try saveData(data, account: account)
    }

    func saveData(_ data: Data, account: String) throws {
        let query = baseQuery(account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw KeychainError.unhandled(updateStatus)
        }

        var item = query
        attributes.forEach { item[$0.key] = $0.value }
        let addStatus = SecItemAdd(item as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw KeychainError.unhandled(addStatus)
        }
    }

    /// Adds a new ThisDeviceOnly item whose value can only be returned after the requested
    /// user-presence policy has been satisfied. Protected keys use unique accounts, so an ACL
    /// is never weakened through an in-place update.
    func saveProtectedData(
        _ data: Data,
        account: String,
        flags: SecAccessControlCreateFlags
    ) throws {
        var accessControlError: Unmanaged<CFError>?
        guard let accessControl = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            flags,
            &accessControlError
        ) else {
            let error = accessControlError?.takeRetainedValue()
            throw KeychainError.accessControl(error)
        }

        var item = baseQuery(account: account)
        item[kSecValueData as String] = data
        item[kSecAttrAccessControl as String] = accessControl
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError.unhandled(status)
        }
    }

    func delete(account: String) throws {
        var query = baseQuery(account: account)
        let context = LAContext()
        context.interactionNotAllowed = true
        query[kSecUseAuthenticationContext as String] = context
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unhandled(status)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}

enum KeychainError: LocalizedError {
    case invalidData
    case decoding(Error)
    case accessControl(CFError?)
    case unhandled(OSStatus)

    var errorDescription: String? {
        switch self {
        case .invalidData:
            return "Keychain item did not contain data"
        case .decoding:
            return "Keychain item could not be decoded"
        case .accessControl(let error):
            return error.map { CFErrorCopyDescription($0) as String }
                ?? "Keychain access control is unavailable"
        case .unhandled(let status):
            let detail = SecCopyErrorMessageString(status, nil) as String?
            return detail ?? "Keychain operation failed (OSStatus \(status))"
        }
    }

    var status: OSStatus? {
        guard case .unhandled(let status) = self else { return nil }
        return status
    }
}
