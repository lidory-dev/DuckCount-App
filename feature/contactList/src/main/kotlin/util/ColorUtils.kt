package util

import androidx.compose.ui.graphics.Color
import kotlin.math.absoluteValue

fun getColorsForContact(uuid: String, isDarkTheme: Boolean = false): ContactColors {
    val hash = uuid.hashCode()
    val lightColors = listOf(
        ContactColors(
            containerColor = Color(0xff775891),
            contentColor = Color(0xfff1dbff),
        ),
        ContactColors(
            containerColor = Color(0xffecad27),
            contentColor = Color(0xff614300),
        ),
        ContactColors(
            containerColor = Color(0xffde853f),
            contentColor = Color(0xff532700),
        ),
        ContactColors(
            containerColor = Color(0xff2ca4d1),
            contentColor = Color(0xff003548),
        ),
        ContactColors(
            containerColor = Color(0xffea8885),
            contentColor = Color(0xff682223),
        ),
        ContactColors(
            containerColor = Color(0xffb8a5ee),
            contentColor = Color(0xff493878),
        ),
    )

    val darkColors = listOf(
        ContactColors(
            containerColor = Color(0xff775891),
            contentColor = Color(0xfff1dbff),
        ),
        ContactColors(
            containerColor = Color(0xffecad27),
            contentColor = Color(0xff614300),
        ),
        ContactColors(
            containerColor = Color(0xffde853f),
            contentColor = Color(0xff532700),
        ),
        ContactColors(
            containerColor = Color(0xff2ca4d1),
            contentColor = Color(0xff003548),
        ),
        ContactColors(
            containerColor = Color(0xffea8885),
            contentColor = Color(0xff682223),
        ),
        ContactColors(
            containerColor = Color(0xffb8a5ee),
            contentColor = Color(0xff493878),
        ),
    )

    val colors = if (isDarkTheme) darkColors else lightColors

    return colors[hash.absoluteValue % colors.size]
}

data class ContactColors(
    val containerColor: Color,
    val contentColor: Color,
)
