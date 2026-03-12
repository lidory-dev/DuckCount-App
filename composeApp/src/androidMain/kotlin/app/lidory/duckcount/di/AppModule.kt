package app.lidory.duckcount.di

import database.di.dbModule
import database.di.dbPlatformModule
import di.expenseListModule
import di.sharedModule

val appModule = listOf(
    sharedModule,
    dbModule,
    dbPlatformModule(),
    expenseListModule,
)
