@file:Suppress("PropertyName")

package database.data.remote

data class CurrencyDto(
    val uuid: String,
    val name: EncryptedFieldDto,
    val symbol: EncryptedFieldDto,
    val has_subunits: Boolean,
)
