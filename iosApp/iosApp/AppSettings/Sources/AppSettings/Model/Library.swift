import Foundation

/// A library the app ships, as the Licences screen lists it, like Android's, from what the
/// AboutLibraries Gradle plugin reads of each one.
public struct Library: Identifiable, Hashable, Sendable {
    /// The library's Maven coordinates, group and artifact, or what stands in for them.
    public let id: String
    public let name: String
    public let version: String?

    /// Who makes it: its organization, or else its developers.
    public let author: String?

    public let website: URL?
    public let licences: [Licence]

    public init(id: String, name: String, version: String?, author: String?, website: URL?, licences: [Licence]) {
        self.id = id
        self.name = name
        self.version = version
        self.author = author
        self.website = website
        self.licences = licences
    }

    /// The libraries in `data`, the `aboutlibraries.json` the AboutLibraries Gradle plugin writes,
    /// by name as the user's language orders them. A licence the file does not describe is left out
    /// of its library's, and a library's name falls back on its coordinates.
    public static func decoding(aboutLibraries data: Data) throws -> [Library] {
        let file = try JSONDecoder().decode(AboutLibrariesFile.self, from: data)

        return file.libraries
            .map { library in
                let author = library.organization?.name?.nonBlank
                    ?? library.developers?.compactMap { $0.name?.nonBlank }.joined(separator: ", ").nonBlank

                return Library(
                    id: library.uniqueId,
                    name: library.name?.nonBlank ?? library.uniqueId,
                    version: library.artifactVersion?.nonBlank,
                    author: author,
                    website: (library.website ?? library.scm?.url).flatMap(URL.init(string:)),
                    licences: (library.licenses ?? []).compactMap { key in
                        file.licenses[key].map { licence in
                            Licence(
                                name: licence.name?.nonBlank ?? key,
                                text: licence.content?.nonBlank,
                                url: licence.url.flatMap(URL.init(string:))
                            )
                        }
                    }
                )
            }
            .sorted { first, second in
                let order = first.name.localizedCaseInsensitiveCompare(second.name)

                return order == .orderedSame ? first.id < second.id : order == .orderedAscending
            }
    }
}

/// A licence a library is shipped under.
public struct Licence: Hashable, Sendable {
    public let name: String

    /// Its full text, when the file holds it.
    public let text: String?

    public let url: URL?

    public init(name: String, text: String?, url: URL?) {
        self.name = name
        self.text = text
        self.url = url
    }
}

/// The part of the AboutLibraries plugin's output the screen reads.
private struct AboutLibrariesFile: Decodable {
    struct Library: Decodable {
        struct Person: Decodable {
            let name: String?
        }

        struct Scm: Decodable {
            let url: String?
        }

        let uniqueId: String
        let name: String?
        let artifactVersion: String?
        let website: String?
        let scm: Scm?
        let organization: Person?
        let developers: [Person]?
        let licenses: [String]?
    }

    struct Licence: Decodable {
        let name: String?
        let content: String?
        let url: String?
    }

    let libraries: [Library]
    let licenses: [String: Licence]
}

extension String {
    /// `self`, or nil when it holds nothing but whitespace.
    fileprivate var nonBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
