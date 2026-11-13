import ContactList
import ExpenseList
import GroupList
import SwiftUI

/// The Groups tab, run by the shared module: the group screens, leading to a group's expenses and to
/// the contact screens, which other packages provide.
struct GroupListScreen: View {
    @State private var callBacks = GroupCallBacksImpl()
    @State private var expenseCallBacks = ExpenseCallBacksImpl()
    @State private var contactCallBacks = ContactCallBacksImpl()

    var body: some View {
        GroupListView(
            callBacks: callBacks,
            destinations: GroupDestinations(
                expenses: { [expenseCallBacks] groupId in
                    AnyView(GroupExpensesView(groupId: groupId, callBacks: expenseCallBacks))
                },
                contactDetail: { [contactCallBacks] userUuid in
                    AnyView(PushedContactDetail(userUuid: userUuid, callBacks: contactCallBacks))
                },
                contactRequests: { [contactCallBacks] in
                    AnyView(ContactRequestListView(callBacks: contactCallBacks))
                }
            )
        )
    }
}

/// A contact's screen pushed from a group member's sheet, which goes back once the contact is
/// removed or blocked, as Android's navigation pops it.
private struct PushedContactDetail: View {
    let userUuid: String
    let callBacks: ContactCallBacksImpl

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ContactDetailView(contactId: userUuid, callBacks: callBacks, onClose: { dismiss() })
    }
}
