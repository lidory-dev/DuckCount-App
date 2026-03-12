package database.di

import androidx.room.RoomDatabase
import database.AppDatabase
import database.getDatabaseBuilder
import org.koin.dsl.module

actual fun dbPlatformModule() = module {
    single<RoomDatabase.Builder<AppDatabase>> {
        getDatabaseBuilder()
    }
}
