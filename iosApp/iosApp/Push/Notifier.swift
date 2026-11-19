import PushSignals
import Shared
import UIKit
import UserNotifications

/// Tells the user who, and what, a sync brought in, as Android's `ContactNotifier`, `GroupNotifier`
/// and `ExpenseNotifier` do, from what the device read and decrypted for itself: a push carries no
/// content, see `PushWake`.
///
/// What a push could say before the sync, iOS shows itself: the nameless text of a visible push, see
/// the notification service extension, told apart by the key of that text, see `StandIn`. The named
/// notification that follows takes its place, in silence, it having rung already, and a pass that
/// read everything takes down whatever nameless one nothing it read took over, see `passEnded`, as
/// Android retires its stand-ins.
///
/// One notification per person who turned up, per group, per person sharing expenses, per expense
/// changed, and per expense of a group, those of a group the sync brought many of told in one. iOS
/// gathers each kind under a thread of its own. A user who hides previews on the lock screen sees each
/// category's nameless text instead, see `Category`.
///
/// Called by the shared module off the main thread, everything done one at a time in the order asked,
/// as Android's are synchronized.
final class Notifier: NSObject, SyncAnnouncer, @unchecked Sendable {
    static let shared = Notifier()

    private let center = UNUserNotificationCenter.current()

    private let lock = NSLock()
    private var tail: Task<Void, Never>?

    private override init() {}

    // MARK: - What the shared module tells

    func announce(announcements: SyncAnnouncements) {
        enqueue { await self.post(announcements) }
    }

    func passEnded(startedAtEpochMillis: Int64, hasFailed: Bool) {
        // A pass that gave up may have left what a nameless one announced unread, for the next pass.
        guard !hasFailed else {
            return
        }

        let startedAt = Date(timeIntervalSince1970: TimeInterval(startedAtEpochMillis) / 1000)

        enqueue { await self.retireStandIns(deliveredBefore: startedAt) }
    }

    // MARK: - What the app asks

    /// Tells iOS what each category's notifications say on a lock screen that hides previews.
    func registerCategories() {
        center.setNotificationCategories(Set(Category.allCases.map(\.category)))
    }

    /// Takes down what `tab` shows now that the user is looking at it, as Android's screens take
    /// theirs down as they show: the requests and new contacts, the expenses that arrived, the groups
    /// joined, left behind and deleted. A change to an expense stays, which may be anywhere in the
    /// list, and so does an expense of a group, which is in the group's screen.
    func tabShown(_ tab: ContentView.Tabs) {
        enqueue { await self.clear(tab) }
    }

    /// Takes down the visible push carrying `encrypted`, which the extension could not hide, lacking
    /// the filtering entitlement, from somebody who may not wake the user. Looked for again a moment
    /// later, the extension perhaps still putting it up as the app is woken.
    func takeDown(pushCarrying encrypted: String) async {
        for attempt in 0..<2 {
            if attempt > 0 {
                try? await Task.sleep(for: .seconds(2))
            }

            let carrying = await center.deliveredNotifications()
                .filter { PushPayload.encryptedSignal(in: $0.request.content.userInfo) == encrypted }
                .map(\.request.identifier)

            center.removeDeliveredNotifications(withIdentifiers: carrying)
        }
    }

    /// How a notification arriving while the app is in front shows: not at all for a push's nameless
    /// text, which Android never puts up on an unlocked phone, the app being woken at once to sync and
    /// say who, see `PushWake`; in the list alone when it is about the tab the user is looking at, or
    /// when it goes up in silence; banner and sound otherwise.
    @MainActor
    func presentation(for notification: UNNotification) -> UNNotificationPresentationOptions {
        if StandIn(notification.request) != nil {
            return []
        }

        if notification.request.content.interruptionLevel == .passive || isShowing(tab(of: notification.request)) {
            return [.list]
        }

        return [.banner, .list, .sound]
    }

    /// Opens what a tapped notification is about, on its tab, see `NotificationOpening`.
    @MainActor
    func open(_ response: UNNotificationResponse) {
        let request = response.notification.request

        guard let tab = tab(of: request) else {
            return
        }

        if let opening = opening(of: request) {
            NotificationRoute.shared.open(opening)
        }

        UserDefaults.standard.set(tab.rawValue, forKey: selectedTabKey)
    }

    // MARK: - Posting

    private func post(_ announcements: SyncAnnouncements) async {
        let notifications = await center.deliveredNotifications()

        var standIns: [StandIn: [String]] = [:]

        for notification in notifications {
            if let kind = StandIn(notification.request) {
                standIns[kind, default: []].append(notification.request.identifier)
            }
        }

        let delivered = Delivered(ids: Set(notifications.map(\.request.identifier)), standIns: standIns)

        await postContacts(
            arrivals: announcements.contactArrivals,
            departures: announcements.contactDepartures,
            delivered: delivered
        )
        await postGroups(announcements)
        await postExpenseArrivals(announcements.expenseArrivals, delivered: delivered)
        await postExpenseChanges(announcements.expenseChanges, delivered: delivered)
        await postGroupExpenses(announcements.groupExpenseUpdates, delivered: delivered)
    }

    /// One per person who turned up. A request rings, asking something of the user; an acceptance
    /// goes up in silence, answering one they sent. The first request takes the nameless one's place.
    private func postContacts(arrivals: [ContactArrival], departures: [String], delivered: Delivered) async {
        var standInIsFree = arrivals.contains { $0.kind == .request } && delivered.isUp(.contactRequest)

        for arrival in arrivals {
            let isRequest = arrival.kind == .request
            let takesOverStandIn = standInIsFree && isRequest

            if takesOverStandIn {
                standInIsFree = false
            }

            let body = if let name = arrival.fullName {
                text(isRequest ? "push.contactRequest.text" : "push.contactAccepted.text", name)
            } else {
                text(isRequest ? "push.contactRequest.locked" : "push.contactAccepted.locked")
            }

            await post(
                id: (isRequest ? "contact-request:" : "contact-accepted:") + arrival.userUuid,
                title: text(isRequest ? "push.contactRequest.title" : "push.contactAccepted.title"),
                body: body,
                thread: "contacts",
                category: isRequest ? .contactRequest : .contactAccepted,
                tab: .contactList,
                opening: isRequest ? .contactRequests : .contact(arrival.userUuid),
                isQuiet: takesOverStandIn || !isRequest
            )

            // Only once the named one is up, so that there is no moment with neither.
            if takesOverStandIn {
                center.removeDeliveredNotifications(withIdentifiers: delivered.ids(of: .contactRequest))
            }
        }

        // Contacts who removed the user, or deleted their account, are no contacts to announce.
        if !departures.isEmpty {
            center.removeDeliveredNotifications(withIdentifiers: departures.map { "contact-accepted:" + $0 })
        }
    }

    /// One per group, saying what was last heard of it.
    private func postGroups(_ announcements: SyncAnnouncements) async {
        for arrival in announcements.groupArrivals {
            await postGroup(
                arrival.groupUuid,
                title: text("push.groupAdded.title"),
                body: arrival.adderFullName.map { text("push.groupAdded.text", $0, arrival.title) }
                    ?? text("push.groupAdded.text.unknown", arrival.title),
                category: .groupAdded
            )
        }

        for removal in announcements.groupRemovals {
            await postGroup(
                removal.groupUuid,
                title: text("push.groupRemoved.title"),
                body: removal.removerFullName.map { text("push.groupRemoved.text", $0, removal.title) }
                    ?? text("push.groupRemoved.text.unknown", removal.title),
                category: .groupRemoved
            )
        }

        for deletion in announcements.groupDeletions {
            await postGroup(
                deletion.groupUuid,
                title: text("push.groupDeleted.title"),
                body: deletion.deleterFullName.map { text("push.groupDeleted.text", $0, deletion.title) }
                    ?? text("push.groupDeleted.text.unknown", deletion.title),
                category: .groupDeleted
            )
        }
    }

    private func postGroup(_ groupUuid: String, title: String, body: String, category: Category) async {
        await post(
            id: "group:" + groupUuid,
            title: title,
            body: body,
            thread: "groups",
            category: category,
            tab: .groupList,
            opening: .group(groupUuid, expenseId: nil),
            isQuiet: false
        )
    }

    /// One per person whose expenses became readable, counting them, and nothing while the user looks
    /// at the expenses, where they go in. The first takes the nameless one's place.
    private func postExpenseArrivals(_ arrivals: [ExpenseArrival], delivered: Delivered) async {
        guard !arrivals.isEmpty else {
            return
        }

        if await isShowing(.expenseList) {
            center.removeDeliveredNotifications(withIdentifiers: delivered.ids(of: .expense))

            return
        }

        var standInIsUp = delivered.isUp(.expense)

        let byCreditor = grouped(arrivals, by: \.creditorUuid)

        for (creditorUuid, expenses) in byCreditor.prefix(maxCreditorsAnnounced) {
            let takesOverStandIn = standInIsUp

            if takesOverStandIn {
                standInIsUp = false
            }

            let body = if let name = expenses[0].creditorFullName {
                text("push.expense.text", name, expenses.count)
            } else {
                text("push.expense.text.unknown", expenses.count)
            }

            await post(
                id: "expense-added:" + creditorUuid,
                title: text("push.expense.title"),
                body: body,
                thread: "expenses",
                category: .expense,
                tab: .expenseList,
                // Several are in the list it opens on, there being no one expense to open.
                opening: expenses.count == 1 ? .expense(expenses[0].expenseUuid) : nil,
                isQuiet: takesOverStandIn
            )

            if takesOverStandIn {
                center.removeDeliveredNotifications(withIdentifiers: delivered.ids(of: .expense))
            }
        }

        // Somebody back from a log-out can read months of everybody's expenses at once: past the first
        // few people, the rest are counted in one.
        let rest = byCreditor.dropFirst(maxCreditorsAnnounced).reduce(0) { $0 + $1.items.count }

        if rest > 0 {
            await post(
                id: "expense-added:more",
                title: text("push.expense.title"),
                body: text("push.expense.summary", rest),
                thread: "expenses",
                category: .expense,
                tab: .expenseList,
                opening: nil,
                isQuiet: false
            )
        }
    }

    /// One per expense its creditor changed, saying what they did. Another change to one still up
    /// replaces it in silence, the expense having rung once.
    private func postExpenseChanges(_ changes: [ExpenseChange], delivered: Delivered) async {
        var standInIsUp = delivered.isUp(.expenseChanged)

        for change in changes {
            let id = "expense-changed:" + change.expenseUuid
            let takesOverStandIn = standInIsUp

            if takesOverStandIn {
                standInIsUp = false
            }

            let key = changeKey(change.kind)
            let body = if let name = change.creditorFullName {
                text(key, name, change.title)
            } else {
                text(key + ".unknown", change.title)
            }

            await post(
                id: id,
                title: text("push.expenseChanged.title"),
                body: body,
                thread: "expense-changes",
                category: .expenseChanged,
                tab: .expenseList,
                // One the user was taken off is no longer theirs to open.
                opening: change.kind == .removed ? nil : .expense(change.expenseUuid),
                isQuiet: takesOverStandIn || delivered.ids.contains(id)
            )

            if takesOverStandIn {
                center.removeDeliveredNotifications(withIdentifiers: delivered.ids(of: .expenseChanged))
            }
        }
    }

    /// One per expense of a group, under a thread of that group's, but for a group the sync brought
    /// many of, which is told in one notification counting them, taking the place of what was said of
    /// each of them before.
    private func postGroupExpenses(_ updates: [GroupExpenseUpdate], delivered: Delivered) async {
        for (groupUuid, ofGroup) in grouped(updates, by: \.groupUuid) {
            let thread = "group-expenses:" + groupUuid

            guard ofGroup.count >= manyGroupExpenseUpdates else {
                for update in ofGroup {
                    let id = "group-expense:" + update.expenseUuid
                    let isAdded = update.kind == .added

                    await post(
                        id: id,
                        title: text(isAdded ? "push.groupExpenseAdded.title" : "push.groupExpenseChanged.title"),
                        body: groupExpenseText(update),
                        thread: thread,
                        category: isAdded ? .groupExpenseAdded : .groupExpenseChanged,
                        tab: .groupList,
                        opening: .group(groupUuid, expenseId: update.expenseUuid),
                        isQuiet: delivered.ids.contains(id)
                    )
                }

                continue
            }

            await post(
                id: "group-expenses:" + groupUuid,
                title: text("push.groupExpenses.title"),
                body: text("push.groupExpenses.text", ofGroup.count, ofGroup[0].groupTitle),
                thread: thread,
                category: .groupExpenses,
                tab: .groupList,
                opening: .group(groupUuid, expenseId: nil),
                isQuiet: false
            )

            center.removeDeliveredNotifications(withIdentifiers: ofGroup.map { "group-expense:" + $0.expenseUuid })
        }
    }

    /// What a member did to an expense of a group, naming them, the expense and the group. A
    /// settlement has no title, the member who paid it knowing it by its amount.
    private func groupExpenseText(_ update: GroupExpenseUpdate) -> String {
        let expense = update.isSettlement ? "\(update.amount) \(update.currencySymbol)" : update.title

        let key = switch update.kind {
        case .added: update.isSettlement ? "push.groupSettlement.added" : "push.groupExpense.added"
        case .deleted: update.isSettlement ? "push.groupSettlement.deleted" : "push.groupExpense.deleted"
        case .edited: "push.groupExpense.edited"
        default: "push.groupExpense.restored"
        }

        if let name = update.authorFullName {
            return text(key, name, expense, update.groupTitle)
        }

        return text(key + ".unknown", expense, update.groupTitle)
    }

    private func changeKey(_ kind: ExpenseChange.Kind) -> String {
        switch kind {
        case .removed: "push.expenseChanged.removed"
        case .edited: "push.expenseChanged.edited"
        case .markedSettled: "push.expenseChanged.markedSettled"
        case .markedNotSettled: "push.expenseChanged.markedNotSettled"
        case .editedAndMarkedSettled: "push.expenseChanged.editedMarkedSettled"
        default: "push.expenseChanged.editedMarkedNotSettled"
        }
    }

    /// Puts one notification up, replacing any up under `id`. One that `isQuiet` goes into the list
    /// without a sound or a banner.
    private func post(
        id: String,
        title: String,
        body: String,
        thread: String,
        category: Category,
        tab: ContentView.Tabs,
        opening: NotificationOpening?,
        isQuiet: Bool
    ) async {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.threadIdentifier = thread
        content.categoryIdentifier = category.rawValue
        content.userInfo = [tabKey: tab.rawValue].merging(opening?.userInfo ?? [:]) { $1 }

        if isQuiet {
            content.interruptionLevel = .passive
        } else {
            content.sound = .default
        }

        do {
            try await center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
        } catch {
            print("Notifier: could not post a notification: \(error)")
        }
    }

    // MARK: - Taking down

    /// Takes down every nameless one put up before a pass that read everything began: what it
    /// announced is in by then, told by name if it was news, or never was, a signal being its
    /// sender's word alone. One put up while the pass ran may be for something it had already gone
    /// past, and stays for the next.
    private func retireStandIns(deliveredBefore startedAt: Date) async {
        let stale = await center.deliveredNotifications()
            .filter { StandIn($0.request) != nil && $0.date < startedAt }
            .map(\.request.identifier)

        center.removeDeliveredNotifications(withIdentifiers: stale)
    }

    private func clear(_ tab: ContentView.Tabs) async {
        let shown: (UNNotificationRequest) -> Bool = switch tab {
        case .contactList:
            {
                StandIn($0) == .contactRequest
                    || $0.identifier.hasPrefix("contact-request:")
                    || $0.identifier.hasPrefix("contact-accepted:")
            }
        // The nameless one stays: what it announced is not in the list until a sync reads it.
        case .expenseList:
            { $0.identifier.hasPrefix("expense-added:") }
        case .groupList:
            { $0.identifier.hasPrefix("group:") }
        case .settings:
            { _ in false }
        }

        let ids = await center.deliveredNotifications().map(\.request).filter(shown).map(\.identifier)

        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    // MARK: - Helpers

    private func enqueue(_ work: @escaping @Sendable () async -> Void) {
        lock.lock()
        defer { lock.unlock() }

        let previous = tail

        tail = Task {
            await previous?.value
            await work()
        }
    }

    @MainActor
    private func isShowing(_ tab: ContentView.Tabs?) -> Bool {
        guard let tab, UIApplication.shared.applicationState == .active else {
            return false
        }

        let selected = UserDefaults.standard.string(forKey: selectedTabKey).flatMap(ContentView.Tabs.init(rawValue:))

        return (selected ?? .expenseList) == tab
    }

    /// The tab a notification is about: its own word for the named ones, the server's collapse id for
    /// the nameless ones a push put up.
    private func tab(of request: UNNotificationRequest) -> ContentView.Tabs? {
        if let tab = (request.content.userInfo[tabKey] as? String).flatMap(ContentView.Tabs.init(rawValue:)) {
            return tab
        }

        switch StandIn(request) {
        case .contactRequest: return .contactList
        case .expense, .expenseChanged: return .expenseList
        case nil: return nil
        }
    }

    /// What a notification opens over its tab: its own word for the named ones, the requests for a
    /// push's nameless contact request, and nothing more for its nameless expenses, which are in the
    /// list the tab opens on.
    private func opening(of request: UNNotificationRequest) -> NotificationOpening? {
        if let opening = NotificationOpening(userInfo: request.content.userInfo) {
            return opening
        }

        return StandIn(request) == .contactRequest ? .contactRequests : nil
    }

    /// The localized text under `key`, with `arguments` put in, plurals included.
    private func text(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: .current, arguments: arguments)
    }

    /// `items` grouped by `key`, in the order each key first comes.
    private func grouped<T>(_ items: [T], by key: (T) -> String) -> [(key: String, items: [T])] {
        var order: [String] = []
        var groups: [String: [T]] = [:]

        for item in items {
            let value = key(item)

            if groups[value] == nil {
                order.append(value)
            }

            groups[value, default: []].append(item)
        }

        return order.map { ($0, groups[$0]!) }
    }
}

/// The nameless notifications a visible push puts up, by the key of the text the server has iOS show,
/// one per kind of push that is visible.
private enum StandIn: String {
    case contactRequest = "push.contactRequest.locked"
    case expense = "push.expense.locked"
    case expenseChanged = "push.expenseChanged.locked"

    /// The kind of nameless notification `request` is, or nil for one of the app's own, which carries
    /// no signal.
    init?(_ request: UNNotificationRequest) {
        let userInfo = request.content.userInfo

        guard
            PushPayload.encryptedSignal(in: userInfo) != nil,
            let aps = userInfo["aps"] as? [String: Any],
            let alert = aps["alert"] as? [String: Any],
            let key = alert["loc-key"] as? String
        else {
            return nil
        }

        self.init(rawValue: key)
    }
}

/// What was up as a sync's announcements were about to go up.
private struct Delivered {
    /// The identifier of every notification up.
    let ids: Set<String>

    /// Those of the nameless ones up, by kind: iOS keeps one per kind, the server sending each kind
    /// under a collapse id of its own, but nothing here counts on it.
    let standIns: [StandIn: [String]]

    func isUp(_ kind: StandIn) -> Bool { standIns[kind]?.isEmpty == false }

    func ids(of kind: StandIn) -> [String] { standIns[kind] ?? [] }
}

/// What each kind of notification says on a lock screen set to hide previews: its title, which names
/// nobody, and its nameless text, as Android's public versions do.
private enum Category: String, CaseIterable {
    case contactRequest
    case contactAccepted
    case expense
    case expenseChanged
    case groupAdded
    case groupRemoved
    case groupDeleted
    case groupExpenseAdded
    case groupExpenseChanged
    case groupExpenses

    var category: UNNotificationCategory {
        UNNotificationCategory(
            identifier: rawValue,
            actions: [],
            intentIdentifiers: [],
            hiddenPreviewsBodyPlaceholder: NSLocalizedString(placeholderKey, comment: ""),
            options: [.hiddenPreviewsShowTitle]
        )
    }

    private var placeholderKey: String {
        switch self {
        case .contactRequest: "push.contactRequest.locked"
        case .contactAccepted: "push.contactAccepted.locked"
        case .expense: "push.expense.locked"
        case .expenseChanged: "push.expenseChanged.locked"
        case .groupAdded: "push.groupAdded.locked"
        case .groupRemoved: "push.groupRemoved.locked"
        case .groupDeleted: "push.groupDeleted.locked"
        case .groupExpenseAdded: "push.groupExpenseAdded.locked"
        case .groupExpenseChanged: "push.groupExpenseChanged.locked"
        case .groupExpenses: "push.groupExpenses.locked"
        }
    }
}

/// Where a named notification says which tab it is about.
private let tabKey = "tab"

/// Where the app keeps the tab the user is on, see `ContentView`.
private let selectedTabKey = "selectedTab"

/// How many people may be named in one sync before the rest are counted in one, as on Android.
private let maxCreditorsAnnounced = 4

/// How many updates to one group's expenses a sync brings before they are told in one, as on Android.
private let manyGroupExpenseUpdates = 4
