package mls.di

import mls.KeyPackageApi
import mls.KeyPackageRepository
import mls.MlsIdentityRepository
import mls.RoomMlsStore
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import uniffi.lib_duckcount.MlsStore

val mlsModule = module {
    singleOf(::RoomMlsStore) { bind<MlsStore>() }
    singleOf(::MlsIdentityRepository)
    singleOf(::KeyPackageApi)
    singleOf(::KeyPackageRepository)
}
