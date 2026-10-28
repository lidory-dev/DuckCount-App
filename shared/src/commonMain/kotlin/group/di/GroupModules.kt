package group.di

import group.GroupApi
import group.GroupCreationSender
import group.GroupCreator
import group.GroupKeyBackUp
import group.GroupLeaver
import group.GroupMembershipChanger
import group.GroupMembershipSender
import group.GroupProfileKeyDelivery
import group.GroupSync
import group.MemberProfileRepository
import org.koin.dsl.module

val groupModule = module {
    single { GroupApi(client = get()) }

    single { GroupKeyBackUp(groupDao = get(), vaultRepository = get()) }

    single {
        MemberProfileRepository(
            memberProfileDao = get(),
            memberProfileVault = get(),
            profileRepository = get(),
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
            profileKeyVault = get(),
            memberProfileRepository = get(),
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
            profileKeyVault = get(),
            memberProfileRepository = get(),
        )
    }

    single {
        GroupLeaver(
            groupApi = get(),
            groupDao = get(),
            vaultRepository = get(),
            mlsIdentityRepository = get(),
            resendScheduler = get(),
        )
    }

    single {
        GroupSync(
            groupApi = get(),
            groupDao = get(),
            groupCreationSender = get(),
            groupMembershipSender = get(),
            groupMembershipChanger = get(),
            groupKeyBackUp = get(),
            groupProfileKeyDelivery = get(),
            memberProfileRepository = get(),
            vaultRepository = get(),
            contactRepository = get(),
            contactDao = get(),
            identityConflictDao = get(),
            mlsIdentityRepository = get(),
            userDao = get(),
            resendScheduler = get(),
        )
    }
}
