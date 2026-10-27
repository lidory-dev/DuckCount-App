package group.di

import group.GroupApi
import group.GroupCreationSender
import group.GroupCreator
import group.GroupKeyBackUp
import group.GroupMembershipChanger
import group.GroupMembershipSender
import group.GroupSync
import org.koin.dsl.module

val groupModule = module {
    single { GroupApi(client = get()) }

    single { GroupKeyBackUp(groupDao = get(), vaultRepository = get()) }

    single {
        GroupCreationSender(
            groupApi = get(),
            groupDao = get(),
            vaultRepository = get(),
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
            groupKeyBackUp = get(),
        )
    }

    single {
        GroupMembershipSender(
            groupApi = get(),
            groupDao = get(),
            groupKeyBackUp = get(),
            mlsIdentityRepository = get(),
            directMessageSender = get(),
            resendScheduler = get(),
        )
    }

    single {
        GroupMembershipChanger(
            contactRepository = get(),
            contactDao = get(),
            identityConflictDao = get(),
            mlsIdentityRepository = get(),
            keyPackageApi = get(),
            groupDao = get(),
            groupMembershipSender = get(),
        )
    }

    single {
        GroupSync(
            groupApi = get(),
            groupDao = get(),
            groupCreationSender = get(),
            groupMembershipSender = get(),
            groupKeyBackUp = get(),
            vaultRepository = get(),
            contactRepository = get(),
            contactDao = get(),
            mlsIdentityRepository = get(),
            userDao = get(),
        )
    }
}
