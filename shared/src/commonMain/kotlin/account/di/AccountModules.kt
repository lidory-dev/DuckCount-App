package account.di

import account.AccountDeletion
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
}
