import Foundation

extension Array {
    /// `self` as every list of people is ordered: the user, `selfUuid`, first, then the others by
    /// name as the user's language sorts them, people with no name last, and ties by UUID, as
    /// Android's `sortedByName` orders them.
    func sortedByName(
        selfUuid: String?,
        uuidOf: (Element) -> String,
        nameOf: (Element) -> String?
    ) -> [Element] {
        sorted { first, second in
            let firstUuid = uuidOf(first)
            let secondUuid = uuidOf(second)

            if (firstUuid == selfUuid) != (secondUuid == selfUuid) {
                return firstUuid == selfUuid
            }

            switch (nameOf(first), nameOf(second)) {
            case let (firstName?, secondName?):
                let order = firstName.localizedCompare(secondName)

                if order != .orderedSame {
                    return order == .orderedAscending
                }
            case (.some, nil):
                return true
            case (nil, .some):
                return false
            case (nil, nil):
                break
            }

            return firstUuid < secondUuid
        }
    }
}

/// The names the device holds for the people a screen shows, as they are read.
public struct NameBook: Equatable, Sendable {
    /// By UUID, for the people the device holds a profile for.
    var known: [String: String] = [:]

    /// The people whose name was looked up, held or not.
    var read: Set<String> = []

    /// The name of `userUuid`, or nil while it is not read, or when the device holds none.
    func name(of userUuid: String) -> String? {
        known[userUuid]
    }

    /// What to call `userUuid`: their name, or what stands in for it once there is none to read, the
    /// device holding no profile for them, and nothing while it is still being read.
    func displayName(of userUuid: String) -> String {
        if let name = known[userUuid] {
            return name
        }

        return read.contains(userUuid) ? NameBook.unknownPerson : ""
    }

    /// What stands in for the name of somebody the device holds no profile for: a UUID says
    /// nothing to the person reading it.
    static var unknownPerson: String {
        String(localized: "Someone else", bundle: .module)
    }
}

/// Follows the names of a set of people that changes as what a screen shows does, see
/// `ExpenseCallBacks.fullNames(of:)`.
@MainActor
final class NameFollower {
    private(set) var book = NameBook()

    private var followed: Set<String>?
    private let task = TaskHandle()
    private let onChange: @MainActor (NameBook) -> Void

    init(onChange: @escaping @MainActor (NameBook) -> Void) {
        self.onChange = onChange
    }

    /// Follows the names of `userUuids` from now on, through `callBacks`, rather than those of the
    /// people followed so far, whose names are kept until the new ones are read.
    func follow(_ userUuids: Set<String>, callBacks: any ExpenseCallBacks) {
        guard userUuids != followed else {
            return
        }

        followed = userUuids

        let names = callBacks.fullNames(of: userUuids)

        task.replace(
            with: Task { [weak self] in
                for await known in names {
                    guard let self, !Task.isCancelled else {
                        return
                    }

                    self.book = NameBook(known: known, read: userUuids)
                    self.onChange(self.book)
                }
            }
        )
    }
}

/// A task that is cancelled once replaced, or once its owner is gone: what follows a stream for a
/// view model, which it must not keep alive.
final class TaskHandle: @unchecked Sendable {
    private var task: Task<Void, Never>?

    func replace(with task: Task<Void, Never>?) {
        self.task?.cancel()
        self.task = task
    }

    deinit {
        task?.cancel()
    }
}
