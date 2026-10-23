package account.di

import account.AccountDeletion
import account.PasswordChange
import org.koin.dsl.module

val accountModule = module {
    single {
        AccountDeletion(
            userApi = get(),
            contactDao = get(),
            contactRepository = get(),
            duckCountClient = get(),
        )
    }

    single {
        PasswordChange(
            userApi = get(),
            sessionApi = get(),
            duckCountClient = get(),
            haveIBeenPwnedClient = get(),
        )
    }
}
