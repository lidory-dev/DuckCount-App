package networking.di

import networking.DuckCountClient
import org.koin.dsl.module

val networkingModule = module {
    single { DuckCountClient() } // TODO: Still needed?
}
