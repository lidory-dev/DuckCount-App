import Foundation

/// The groups tab's state, as Android's `GroupListViewModel` holds it.
@MainActor
public final class GroupListViewModel: ObservableObject {
    /// Every group on the device, in the order the shared module gives them, nil until read.
    @Published private(set) var groups: [GroupSummary]?

    private let callBacks: any GroupCallBacks
    private let groupsTask = TaskHandle()

    public init(callBacks: any GroupCallBacks) {
        self.callBacks = callBacks

        follow(callBacks.groups(), owner: self, into: groupsTask) { owner, groups in
            owner.groups = groups
        }
    }

    /// Brings the contacts, the groups and their expenses up to date, which the list shows as they
    /// are stored.
    func refresh() async {
        await callBacks.refresh()
    }
}

/// A group's screen's state, as Android's `GroupDetailViewModel` holds it: the group with its
/// members, how many of its expenses this device cannot read yet, and whether it is gone.
@MainActor
final class GroupDetailViewModel: ObservableObject {
    /// The group as the device holds it, nil until read.
    @Published private(set) var group: GroupDetails?

    /// How many of the group's expenses this device cannot read yet, which the balances leave out.
    @Published private(set) var unreadableExpenseCount = 0

    /// True once the device holds the group no more: its owner deleted it from here, the user
    /// deleted it from this device, or the server refused its creation. A state rather than an
    /// event: it can happen while the app is in the background, and the screen closes once the
    /// user is back. Android leaves its screen blank then; a split view would keep showing it.
    @Published private(set) var isGone = false

    let groupId: String

    private let callBacks: any GroupCallBacks
    private let groupTask = TaskHandle()
    private let unreadableTask = TaskHandle()

    init(groupId: String, callBacks: any GroupCallBacks) {
        self.groupId = groupId
        self.callBacks = callBacks

        follow(callBacks.group(id: groupId), owner: self, into: groupTask) { owner, group in
            if let group {
                owner.group = group
            } else {
                owner.isGone = true
            }
        }

        follow(callBacks.unreadableExpenseCount(groupId: groupId), owner: self, into: unreadableTask) { owner, count in
            owner.unreadableExpenseCount = count
        }
    }

    /// Whether a settlement can be recorded in the group: one is an expense, which a group the user
    /// is no longer in takes no more of. Not before the group is read either.
    var canSettle: Bool {
        group.map { !$0.isLeft } ?? false
    }

    /// Brings the group and its expenses up to date, which it shows as they are stored meanwhile.
    func refresh() async {
        await callBacks.refreshGroup(id: groupId)
    }
}
