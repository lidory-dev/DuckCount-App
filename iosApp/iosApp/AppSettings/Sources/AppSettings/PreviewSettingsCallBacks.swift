import Foundation

/// Stands in for the shared module in Xcode's previews.
@MainActor
final class PreviewSettingsCallBacks: SettingsCallBacks {
    let maxUsernameLength = 20
    let maxFullNameLength = 50
    let minimumPasswordLength = 15
    let learnMoreLink = URL(string: "https://lidory.com")!
    let sourceCodeLink = URL(string: "https://github.com/lidory-dev/DuckCount-App")!
    let termsOfUseLink = URL(string: "https://lidory.com/duckcount/conditions-utilisation")!
    let privacyPolicyLink = URL(string: "https://lidory.com/duckcount/confidentialite")!
    let appVersion = "1.0.0"

    let libraries = [
        Library(
            id: "io.ktor:ktor-client-core",
            name: "ktor-client-core",
            version: "3.5.1",
            author: "JetBrains",
            website: URL(string: "https://ktor.io"),
            licences: [Licence(name: "Apache License 2.0", text: "Apache License\nVersion 2.0, January 2004", url: nil)]
        ),
        Library(
            id: "net.zetetic:SQLCipher.swift",
            name: "SQLCipher",
            version: "4.19.0",
            author: "Zetetic LLC",
            website: URL(string: "https://www.zetetic.net/sqlcipher/"),
            licences: [Licence(name: "SQLCipher Community Edition Open Source License", text: "Copyright (c) 2025, ZETETIC LLC", url: nil)]
        ),
    ]

    func authenticatedUserUuid() -> String? {
        "c0ffee00-0000-4000-8000-000000000001"
    }

    func authenticatedUsername() -> String? {
        "ada"
    }

    func fullName(of userUuid: String) -> AsyncStream<String?> {
        AsyncStream { $0.yield("Ada Lovelace") }
    }

    func refreshProfile(userUuid: String) async {}

    func updateProfile(userUuid: String, fullName: String) async -> ProfileSaveError? { nil }

    func reservedUsername() -> AsyncStream<String?> {
        AsyncStream { $0.yield("ada") }
    }

    func refreshReservedUsername() async {}

    func validateUsername(_ username: String) -> UsernameInputError? { nil }

    func normalizeUsername(_ username: String) -> String {
        username.trimmedUsername
    }

    func reserveUsername(_ username: String) async -> ReserveUsernameError? { nil }

    func changePassword(currentPassword: String, newPassword: String) async -> PasswordChangeError? { nil }

    func logOut() async {}

    func deleteAccount(password: String) async -> AccountDeletionError { .wrongPassword }
}
