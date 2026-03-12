package database.currency

import androidx.room.TypeConverter
import kotlinx.serialization.json.Json

class CurrencyConverter {
    @TypeConverter
    fun fromCurrency(currency: Currency): String {
        return Json.encodeToString(currency)
    }

    @TypeConverter
    fun toCurrency(json: String): Currency {
        return Json.decodeFromString(json)
    }
}
