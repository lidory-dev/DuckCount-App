import Foundation
import Testing

@testable import GroupList

private let failures: [GroupFailure] = [.noInternet, .timeout, .serverUnreachable, .groupLeft, .rateLimited, .awaitingOwner, .unknown]
private let refusals: [MemberRefusal] = [.notContacts, .identityChanged, .unreachable, .deleted]

/// What the group screens say of each failure, as Android's `model/*MessageTest`s say it: each
/// case in its own words, compared with what the String Catalog holds for its own key in the
/// language the tests run in, whichever it is, so that two cases whose words were swapped fail.
/// `CatalogTests` checks the words themselves, in each language.
struct MessageTests {
    @Test func eachFailureIsToldAsSuch() {
        #expect(GroupFailure.noInternet.message == String(localized: "No connection, check your network and try again", bundle: .module))
        #expect(GroupFailure.timeout.message == String(localized: "The server took too long to answer, please try again", bundle: .module))
        #expect(GroupFailure.serverUnreachable.message == String(localized: "Server is unreachable, please try again later", bundle: .module))
        #expect(GroupFailure.rateLimited.message == String(localized: "Too many changes, please try again later", bundle: .module))
        #expect(
            GroupFailure.awaitingOwner.message
                == String(localized: "The group is waiting for its owner to come online, please try again later", bundle: .module)
        )
        #expect(GroupFailure.unknown.message == String(localized: "Something went wrong, please try again", bundle: .module))
    }

    @Test func everyFailureIsToldInWordsOfItsOwn() {
        #expect(Set(failures.map(\.message)).count == failures.count)
    }

    /// The server says so before this device's next sync reads it.
    @Test func aGroupLeftIsToldAsTheGroupSaysIt() {
        #expect(GroupFailure.groupLeft.message == GroupNotices.left)
        #expect(GroupNotices.left == String(localized: "You’re no longer a member of this group", bundle: .module))
    }

    /// The group cannot vouch for them: nothing the user can do from that sheet about it.
    @Test func aMemberTheGroupCannotVouchForIsToldAsSuch() {
        #expect(
            MemberContactRequestError.notInGroup.message
                == String(
                    localized: "This member’s identity can’t be confirmed from this group on this device, so the request was not sent",
                    bundle: .module
                )
        )
    }

    @Test func aChangedSafetyNumberIsToldAsSuch() {
        #expect(
            MemberContactRequestError.identityChanged.message
                == String(localized: "Your safety number with this member has changed, so the request was not sent", bundle: .module)
        )
    }

    @Test func everyOtherContactRequestErrorIsToldAsSuch() {
        #expect(
            MemberContactRequestError.notFound.message
                == String(localized: "This member can’t receive a contact request right now, please try again later", bundle: .module)
        )
        #expect(MemberContactRequestError.alreadyContact.message == String(localized: "This member is already one of your contacts", bundle: .module))
        #expect(MemberContactRequestError.alreadyPending.message == String(localized: "You already sent this member a contact request", bundle: .module))
        #expect(MemberContactRequestError.rateLimited.message == String(localized: "Too many attempts, please try again later", bundle: .module))
        #expect(
            MemberContactRequestError.blocked.message
                == String(localized: "You blocked this member. Unblock them from your contacts to send a request.", bundle: .module)
        )
        #expect(MemberContactRequestError.noInternet.message == String(localized: "No internet connection", bundle: .module))
        #expect(
            MemberContactRequestError.timeout.message
                == String(
                    localized: "member_contact_request_error_timeout",
                    defaultValue: "The server took too long to answer, please try again",
                    bundle: .module
                )
        )
        #expect(
            MemberContactRequestError.serverUnreachable.message
                == String(localized: "The server is unreachable, please try again later", bundle: .module)
        )
        #expect(MemberContactRequestError.unknown.message == String(localized: "The contact request could not be sent", bundle: .module))
    }

    @Test func everyContactRequestErrorIsToldInWordsOfItsOwn() {
        let errors: [MemberContactRequestError] = [
            .notFound, .alreadyContact, .alreadyPending, .rateLimited, .identityChanged, .notInGroup, .blocked,
            .noInternet, .timeout, .serverUnreachable, .unknown,
        ]

        #expect(Set(errors.map(\.message)).count == errors.count)
    }

    @Test func aChangeOnItsWayToTheServerIsToldAsSuch() {
        #expect(
            GroupEditError.changePending.message
                == String(localized: "The group can be edited once its last change reaches the server", bundle: .module)
        )
    }

    @Test func anEditMadeOverAVersionTheServerReplacedIsToldAsSuch() {
        #expect(GroupEditError.outdated.message == String(localized: "The group was edited in the meantime, please save again", bundle: .module))
    }

    @Test func anEditToWhatTheGroupShowsAlreadyIsToldAsSuch() {
        #expect(GroupEditError.unchanged.message == String(localized: "The group already has this title and description", bundle: .module))
    }

    @Test func whatKeptAnEditFromGoingThroughIsToldAsForACreation() {
        for failure in failures {
            #expect(GroupEditError.failure(failure).message == failure.message)
        }
    }

    @Test func theDeviceLockingIsToldAsSuch() {
        #expect(
            GroupHistoryError.deviceLocked.message
                == String(localized: "The phone locked before the history could be read, please try again", bundle: .module)
        )
    }

    @Test func aHistoryThisDeviceCannotReadYetIsToldAsSuch() {
        #expect(
            GroupHistoryError.unreadable.message
                == String(localized: "The history can’t be read on this device yet, please try again in a moment", bundle: .module)
        )
    }

    @Test func whatKeptAHistoryFromBeingReadIsToldAsForACreation() {
        for failure in failures {
            #expect(GroupHistoryError.failure(failure).message == failure.message)
        }
    }

    /// In the order the picker lists them, worded for how many they are.
    @Test func membersWhoCouldNotBeAddedAreToldAsSuch() {
        for names in [["Ada"], ["Ada", "Bob"]] {
            let count = names.count
            let listed = names.formatted(.list(type: .and))

            #expect(
                MemberRefusal.notContacts.additionMessage(names: names)
                    == String(localized: "\(count) not added for not being contacts: \(listed)", bundle: .module)
            )
            #expect(
                MemberRefusal.identityChanged.additionMessage(names: names)
                    == String(localized: "\(count) not added for a changed safety number: \(listed)", bundle: .module)
            )
            #expect(
                MemberRefusal.unreachable.additionMessage(names: names)
                    == String(localized: "\(count) not added for being unreachable: \(listed)", bundle: .module)
            )
            #expect(
                MemberRefusal.deleted.additionMessage(names: names)
                    == String(localized: "\(count) not added for a deleted account: \(listed)", bundle: .module)
            )
        }
    }

    /// Which say the group was not created.
    @Test func membersAGroupCouldNotBeCreatedWithAreToldAsSuch() {
        for names in [["Ada"], ["Ada", "Bob"]] {
            let count = names.count
            let listed = names.formatted(.list(type: .and))

            #expect(
                MemberRefusal.notContacts.creationMessage(names: names)
                    == String(localized: "\(count) refused for not being contacts: \(listed)", bundle: .module)
            )
            #expect(
                MemberRefusal.identityChanged.creationMessage(names: names)
                    == String(localized: "\(count) refused for a changed safety number: \(listed)", bundle: .module)
            )
            #expect(
                MemberRefusal.unreachable.creationMessage(names: names)
                    == String(localized: "\(count) refused for being unreachable: \(listed)", bundle: .module)
            )
            #expect(
                MemberRefusal.deleted.creationMessage(names: names)
                    == String(localized: "\(count) refused for a deleted account: \(listed)", bundle: .module)
            )
        }
    }

    /// Each with the names it applies to, which the catalog words, not the key.
    @Test func everyRefusalNamesWhoItIsAbout() {
        for refusal in refusals {
            for message in [refusal.creationMessage(names: ["Ada", "Bob"]), refusal.additionMessage(names: ["Ada"])] {
                #expect(message.contains("Ada"))
                #expect(!message.contains("refused for"))
                #expect(!message.contains("not added for"))
            }
        }
    }

    @Test func everyRefusalOfAnAdditionIsToldInWordsOfItsOwn() {
        #expect(Set(refusals.map { $0.additionMessage(names: ["Ada", "Bob"]) }).count == refusals.count)
        #expect(Set(refusals.map { $0.creationMessage(names: ["Ada", "Bob"]) }).count == refusals.count)
    }

    /// A creation's says the group was not created. Each refusal has a key of its own for an
    /// addition, which `membersWhoCouldNotBeAddedAreToldAsSuch` pins, as Android's has a resource of
    /// its own; these two are the ones whose words differ too, the others saying the same either way.
    @Test func anAdditionIsNeverToldInTheWordsOfACreation() {
        for refusal in [MemberRefusal.identityChanged, .unreachable] {
            for names in [["Ada"], ["Ada", "Bob"]] {
                #expect(refusal.additionMessage(names: names) != refusal.creationMessage(names: names))
            }
        }
    }

    /// Worded for one of them, or for several.
    @Test func aRefusalIsWordedForHowManyItIsAbout() {
        #expect(MemberRefusal.deleted.creationMessage(names: ["Ada"]) != MemberRefusal.deleted.creationMessage(names: ["Ada", "Bob"]))
        #expect(MemberRefusal.identityChanged.additionMessage(names: ["Ada"]) != MemberRefusal.identityChanged.additionMessage(names: ["Ada", "Bob"]))
    }

    @Test func membersAreListedAsTheUsersLanguageListsThem() {
        let message = MemberRefusal.notContacts.creationMessage(names: ["Ada", "Bob", "Carol"])

        #expect(message.contains(["Ada", "Bob", "Carol"].formatted(.list(type: .and))))
    }

    @Test func aCreationOrAdditionErrorIsToldAsWhatItHolds() {
        #expect(GroupCreationError.failure(.timeout).message == GroupFailure.timeout.message)
        #expect(GroupCreationError.members(.deleted, names: ["Ada"]).message == MemberRefusal.deleted.creationMessage(names: ["Ada"]))
        #expect(MembershipChangeError.failure(.timeout).message == GroupFailure.timeout.message)
        #expect(MembershipChangeError.members(.deleted, names: ["Ada"]).message == MemberRefusal.deleted.additionMessage(names: ["Ada"]))
    }

    /// The members listed show it.
    @Test func aChangeMadeTellsNothingAndOneWaitingSaysSo() {
        #expect(MembershipChangeResult.made.message == nil)
        #expect(
            MembershipChangeResult.pending.message
                == String(localized: "The change will be sent as soon as the server can take it", bundle: .module)
        )
        #expect(MembershipChangeResult.failed(.failure(.unknown)).message == GroupFailure.unknown.message)
    }

    @Test func whyThePickerListsNobodyIsToldAsSuch() {
        #expect(
            NobodyToPick.noContacts.message
                == String(localized: "You don’t have any contacts to add yet. Add some from the Contacts tab.", bundle: .module)
        )
        #expect(
            NobodyToPick.allMembers.message == String(localized: "All the contacts you can add are already in this group", bundle: .module)
        )
    }

    @Test func aGroupNoLongerTheUsersIsMarkedAsWhyItIsNot() {
        #expect(GroupNotices.marker(isLeft: false, isDeleted: false) == nil)
        #expect(GroupNotices.marker(isLeft: true, isDeleted: false) == String(localized: "No longer a member", bundle: .module))
        #expect(GroupNotices.marker(isLeft: true, isDeleted: true) == String(localized: "Deleted by its owner", bundle: .module))
        #expect(GroupNotices.notice(isDeleted: false) == GroupNotices.left)
        #expect(GroupNotices.notice(isDeleted: true) == GroupNotices.deleted)
        #expect(
            GroupNotices.deleted
                == String(localized: "The owner deleted this group. What you had of it stays on this device, read-only.", bundle: .module)
        )
    }
}

/// The catalog's words in each of its languages, read from each language's own table whatever the
/// tests run in: the plurals Android has, and French's own order and spacing.
struct CatalogTests {
    private func string(_ key: String, in language: String, _ arguments: any CVarArg...) -> String {
        let path = Bundle.module.path(forResource: language, ofType: "lproj")!
        let format = Bundle(path: path)!.localizedString(forKey: key, value: nil, table: nil)

        return String(format: format, locale: Locale(identifier: language), arguments: arguments)
    }

    @Test func aRefusalIsWordedForOneOrSeveralInEnglish() {
        #expect(string("%lld refused for a deleted account: %@", in: "en", 1, "Ada") == "Ada deleted their account")
        #expect(string("%lld refused for a deleted account: %@", in: "en", 2, "Ada and Bob") == "Ada and Bob deleted their accounts")
        #expect(
            string("%lld not added for a changed safety number: %@", in: "en", 2, "Ada and Bob")
                == "Your safety numbers with Ada and Bob have changed, so nobody was added"
        )
    }

    @Test func aRefusalIsWordedForOneOrSeveralInFrench() {
        #expect(string("%lld refused for a deleted account: %@", in: "fr", 1, "Ada") == "Ada a supprimé son compte")
        #expect(string("%lld refused for a deleted account: %@", in: "fr", 2, "Ada et Bob") == "Ada et Bob ont supprimé leur compte")
        #expect(
            string("%lld refused for a changed safety number: %@", in: "fr", 1, "Ada")
                == "Votre numéro de sécurité avec Ada a changé, le groupe n’a pas été créé"
        )
    }

    @Test func countsAreWordedForOneOrSeveral() {
        #expect(string("%lld members", in: "en", 1) == "1 member")
        #expect(string("%lld members", in: "en", 3) == "3 members")
        #expect(string("%lld members", in: "fr", 1) == "1 membre")
        #expect(string("Members (%lld/%lld)", in: "en", 1, 100) == "Member (1/100)")
        #expect(string("Members (%lld/%lld)", in: "fr", 2, 100) == "Membres (2/100)")
        #expect(string("%lld/%lld members", in: "en", 2, 100) == "2/100 members")
        #expect(string("%lld/%lld members", in: "fr", 1, 100) == "1/100 membre")
        #expect(
            string("%lld expenses can’t be read on this device yet, so the balances leave them out", in: "en", 1)
                == "1 expense can’t be read on this device yet, so the balances leave it out"
        )
    }

    /// French says what is owed before whom it is owed to.
    @Test func frenchPutsTheAmountBeforeWhomItIsOwedTo() {
        #expect(string("You owe %@ %@", in: "fr", "Bob", "5.00 €") == "Vous devez 5.00 € à Bob")
        #expect(string("%@ owes %@ %@", in: "fr", "Carol", "Bob", "5.00 €") == "Carol doit 5.00 € à Bob")
        #expect(string("%@ owes you %@", in: "fr", "Ada", "20.00 €") == "Ada vous doit 20.00 €")
        #expect(string("You owe %@ %@", in: "en", "Bob", "5.00 €") == "You owe Bob 5.00 €")
    }

    /// A no-break space inside French quotes and before a question mark, which no line ever breaks
    /// at.
    @Test func frenchSpacesItsQuotesAndQuestionsWithNoBreakSpaces() {
        #expect(string("Remove %@ from “%@”?", in: "fr", "Ada", "Ski") == "Retirer Ada du groupe «\u{00A0}Ski\u{00A0}»\u{00A0}?")
        #expect(string("Leave “%@”?", in: "fr", "Ski") == "Quitter le groupe «\u{00A0}Ski\u{00A0}»\u{00A0}?")
    }

    /// The same English as a group change's, which French words differently.
    @Test func aContactRequestTimingOutIsWordedOfItsOwn() {
        #expect(string("member_contact_request_error_timeout", in: "en") == "The server took too long to answer, please try again")
        #expect(string("member_contact_request_error_timeout", in: "fr") == "Le serveur a mis trop de temps à répondre, réessayez")
        #expect(
            string("The server took too long to answer, please try again", in: "fr")
                == "Le serveur a mis trop de temps à répondre, réessayez plus tard"
        )
    }
}
