import Foundation

enum Formatters {
    static func number(_ value: Double, digits: Int = 1) -> String {
        String(format: "%.\(digits)f", value)
    }

    static func percent(_ value: Double, digits: Int = 1) -> String {
        "\(number(value, digits: digits))%"
    }

    static func bytes(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .binary)
    }

    static func rate(_ value: Int64) -> String {
        "\(bytes(value))/s"
    }

    static func uptime(_ seconds: Int64) -> String {
        let days = seconds / 86_400
        let hours = seconds % 86_400 / 3_600
        let minutes = seconds % 3_600 / 60
        if days > 0 { return "\(days)d \(hours)h" }
        if hours > 0 { return "\(hours)h \(minutes)m" }
        return "\(minutes)m"
    }

    static func maskIPAddress(_ value: String) -> String {
        let value = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return "" }

        // Komari may already have applied its guest-address mask.
        if value.contains("*") { return value }

        let unwrapped: String
        if value.hasPrefix("["), value.hasSuffix("]") {
            unwrapped = String(value.dropFirst().dropLast())
        } else {
            unwrapped = value
        }
        let address = String(unwrapped.split(separator: "%", maxSplits: 1).first ?? "")

        if isIPv4(address) {
            return "\(address.split(separator: ".", omittingEmptySubsequences: false)[0]).*.*.*"
        }
        if isIPv6(address) {
            // Do not expose the host component of compressed loopback/mapped addresses.
            guard let first = address.split(separator: ":", omittingEmptySubsequences: false).first,
                  !first.isEmpty else {
                return "*:*:*:*:*:*:*:*"
            }
            return "\(first):*:*:*:*:*:*:*"
        }
        return String(repeating: "*", count: value.count)
    }

    private static func isIPv4(_ value: String) -> Bool {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        return parts.allSatisfy { part in
            guard !part.isEmpty, part.allSatisfy(\.isNumber), let octet = Int(part) else {
                return false
            }
            return (0...255).contains(octet)
        }
    }

    private static func isIPv6(_ value: String) -> Bool {
        guard value.contains(":"), !value.isEmpty else { return false }
        let parts = value.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count >= 3, parts.count <= 9 else { return false }
        return parts.enumerated().allSatisfy { index, part in
            if part.isEmpty { return true }
            if index == parts.count - 1, part.contains(".") { return isIPv4(String(part)) }
            return part.count <= 4 && part.allSatisfy(\.isHexDigit)
        }
    }
}

extension String {
    func trimmedTrailingSlash() -> String {
        var value = self
        while value.hasSuffix("/") {
            value.removeLast()
        }
        return value
    }
}
