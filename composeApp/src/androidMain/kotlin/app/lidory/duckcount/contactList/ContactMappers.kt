package app.lidory.duckcount.contactList

import model.User
import database.user.User as UserDB

fun User.toDb(): UserDB = UserDB(
    uuid = uuid,
    username = username,
    fullName = fullName,
)
