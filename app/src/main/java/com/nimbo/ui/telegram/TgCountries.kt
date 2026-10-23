package com.nimbo.ui.telegram

// Prefijos telefónicos. Sin lib externa: lista corta, suficiente.
data class TgCountry(val name: String, val dial: String)

val TgCountries = listOf(
    TgCountry("Argentina", "54"),
    TgCountry("Bolivia", "591"),
    TgCountry("Brasil", "55"),
    TgCountry("Chile", "56"),
    TgCountry("Colombia", "57"),
    TgCountry("Costa Rica", "506"),
    TgCountry("Cuba", "53"),
    TgCountry("Ecuador", "593"),
    TgCountry("El Salvador", "503"),
    TgCountry("España", "34"),
    TgCountry("Estados Unidos", "1"),
    TgCountry("Guatemala", "502"),
    TgCountry("Honduras", "504"),
    TgCountry("México", "52"),
    TgCountry("Nicaragua", "505"),
    TgCountry("Panamá", "507"),
    TgCountry("Paraguay", "595"),
    TgCountry("Perú", "51"),
    TgCountry("Portugal", "351"),
    TgCountry("Puerto Rico", "1"),
    TgCountry("Reino Unido", "44"),
    TgCountry("República Dominicana", "1"),
    TgCountry("Uruguay", "598"),
    TgCountry("Venezuela", "58")
)

fun defaultCountry(): TgCountry = TgCountries.first { it.name == "España" }

// Normaliza a formato internacional: prefijo + dígitos sin ceros troncales.
fun fullPhone(dial: String, phone: String): String =
    dial.filter { it.isDigit() } + phone.filter { it.isDigit() }.dropWhile { it == '0' }
