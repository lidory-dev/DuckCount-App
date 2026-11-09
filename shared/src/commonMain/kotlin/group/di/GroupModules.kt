package group.di

import group.GroupApi
import group.GroupCreationSender
import group.GroupCreator
import group.GroupEditor
import group.GroupExpenseApi
import group.GroupExpenseHistory
import group.GroupExpenseSync
import group.GroupExpenseWriter
import group.GroupHistory
import group.GroupKeyChain
import group.GroupKeyCopies
import group.GroupKeyLinks
import group.GroupDeleter
import group.GroupLeaver
import group.GroupMembershipChanger
import group.GroupMembershipSender
import group.GroupProfileKeyDelivery
import group.GroupRejoiner
import group.GroupSync
import group.MemberProfileRepository
import org.koin.dsl.module

val groupModule = module {
    single { GroupApi(client = get()) }

    single { GroupKeyCopies(vaultRepository = get(), groupApi = get(), groupDao = get()) }

    single { GroupKeyChain(groupApi = get(), groupDao = get()) }

    single { GroupKeyLinks(groupApi = get(), groupDao = get()) }

    single {
        MemberProfileRepository(
            memberProfileDao = get(),
            memberProfileVault = get(),
            profileRepository = get(),
            groupDao = get(),
        )
    }

    single {
        GroupProfileKeyDelivery(
            profileKeyVault = get(),
            groupDao = get(),
            mlsIdentityRepository = get(),
            groupApi = get(),
            resendScheduler = get(),
        )
    }

    single {
        GroupCreationSender(
            groupApi = get(),
            groupDao = get(),
            mlsIdentityRepository = get(),
            directMessageSender = get(),
            resendScheduler = get(),
        )
    }

    single {
        GroupCreator(
            contactRepository = get(),
            contactDao = get(),
            identityConflictDao = get(),
            mlsIdentityRepository = get(),
            keyPackageApi = get(),
            groupDao = get(),
            groupCreationSender = get(),
            groupKeyCopies = get(),
            profileKeyVault = get(),
            memberProfileRepository = get(),
        )
    }

    single {
        GroupMembershipSender(
            groupApi = get(),
            groupDao = get(),
            mlsIdentityRepository = get(),
            directMessageSender = get(),
            wakeList = get(),
            resendScheduler = get(),
        )
    }

    single {
        GroupRejoiner(
            groupApi = get(),
            groupDao = get(),
            groupKeyCopies = get(),
            groupMembershipSender = get(),
            mlsIdentityRepository = get(),
            memberProfileVault = get(),
        )
    }

    single {
        GroupMembershipChanger(
            contactRepository = get(),
            contactDao = get(),
            identityConflictDao = get(),
            mlsIdentityRepository = get(),
            groupKeyCopies = get(),
            keyPackageApi = get(),
            groupDao = get(),
            groupMembershipSender = get(),
            profileKeyVault = get(),
            memberProfileRepository = get(),
            // The sync removes those who departed through this very changer.
            groupSync = lazy { get<GroupSync>() },
            groupSyncAnnouncer = get(),
        )
    }

    single {
        GroupEditor(
            groupApi = get(),
            groupDao = get(),
            groupKeyChain = get(),
            groupSync = get(),
            groupSyncAnnouncer = get(),
            mlsIdentityRepository = get(),
        )
    }

    single {
        GroupHistory(
            groupApi = get(),
            groupDao = get(),
            groupKeyChain = get(),
            mlsIdentityRepository = get(),
        )
    }

    single {
        GroupLeaver(
            groupApi = get(),
            groupDao = get(),
            mlsIdentityRepository = get(),
            wakeList = get(),
            resendScheduler = get(),
        )
    }

    single {
        GroupDeleter(
            groupApi = get(),
            groupDao = get(),
            mlsIdentityRepository = get(),
            wakeList = get(),
        )
    }

    single { GroupExpenseApi(client = get()) }

    single {
        GroupExpenseSync(
            groupExpenseApi = get(),
            groupKeyChain = get(),
            groupDao = get(),
            expenseDao = get(),
            listedGroupExpenseDao = get(),
            mlsIdentityRepository = get(),
        )
    }

    single {
        GroupExpenseWriter(
            groupExpenseApi = get(),
            groupDao = get(),
            expenseDao = get(),
            groupSync = get(),
            groupSyncAnnouncer = get(),
            groupExpenseSync = get(),
            mlsIdentityRepository = get(),
        )
    }

    single {
        GroupExpenseHistory(
            groupExpenseApi = get(),
            groupKeyChain = get(),
            groupDao = get(),
            expenseDao = get(),
            mlsIdentityRepository = get(),
        )
    }

    single {
        GroupSync(
            groupApi = get(),
            groupDao = get(),
            groupCreationSender = get(),
            groupMembershipSender = get(),
            groupMembershipChanger = get(),
            groupKeyCopies = get(),
            groupKeyChain = get(),
            groupKeyLinks = get(),
            groupRejoiner = get(),
            groupProfileKeyDelivery = get(),
            memberProfileRepository = get(),
            contactRepository = get(),
            contactDao = get(),
            identityConflictDao = get(),
            mlsIdentityRepository = get(),
            userDao = get(),
            wakeList = get(),
            resendScheduler = get(),
        )
    }
}
