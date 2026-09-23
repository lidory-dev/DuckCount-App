package channel.di

import channel.DirectChannelRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val channelModule = module {
    singleOf(::DirectChannelRepository)
}
