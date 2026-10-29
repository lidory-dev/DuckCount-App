package group.di

import group.GroupApi
import group.GroupCreationSender
import group.GroupCreator
import group.GroupEditor
import group.GroupKeyCopies
import group.GroupLeaver
import group.GroupMembershipChanger
import group.GroupMembershipSender
import group.GroupProfileKeyDelivery
import group.GroupSync
import group.MemberProfileRepository
import org.koin.dsl.module

val groupModule = module {
    single { GroupApi(client = get()) }

    single { GroupKeyCopies(vaultRepository = get(), groupApi = get(), groupDao = get()) }

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
            groupKeyCopies = get(),
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
        GroupEditor(
            groupApi = get(),
            groupDao = get(),
            mlsIdentityRepository = get(),
        )
    }

    single {
        GroupLeaver(
            groupApi = get(),
            groupDao = get(),
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
            groupKeyCopies = get(),
            groupProfileKeyDelivery = get(),
            memberProfileRepository = get(),
            contactRepository = get(),
            contactDao = get(),
            identityConflictDao = get(),
            mlsIdentityRepository = get(),
            userDao = get(),
            resendScheduler = get(),
        )
    }
}
