package com.nimbo.feature.auth

// Prefijos telefónicos. Sin lib externa: lista corta, suficiente.
// Nombre traducido por el sistema a partir del código ISO.
data class TgCountry(val iso: String, val dial: String) {
    val name: String get() = java.util.Locale.Builder().setRegion(iso).build().displayCountry
}

val TgCountries = listOf(
    TgCountry("AR", "54"),
    TgCountry("BO", "591"),
    TgCountry("BR", "55"),
    TgCountry("CL", "56"),
    TgCountry("CO", "57"),
    TgCountry("CR", "506"),
    TgCountry("CU", "53"),
    TgCountry("EC", "593"),
    TgCountry("SV", "503"),
    TgCountry("ES", "34"),
    TgCountry("US", "1"),
    TgCountry("FR", "33"),
    TgCountry("GT", "502"),
    TgCountry("HN", "504"),
    TgCountry("JP", "81"),
    TgCountry("MX", "52"),
    TgCountry("NI", "505"),
    TgCountry("PA", "507"),
    TgCountry("PY", "595"),
    TgCountry("PE", "51"),
    TgCountry("PT", "351"),
    TgCountry("PR", "1"),
    TgCountry("GB", "44"),
    TgCountry("DO", "1"),
    TgCountry("UY", "598"),
    TgCountry("VE", "58")
)

fun defaultCountry(): TgCountry = TgCountries.first { it.iso == "ES" }

// Normaliza a formato internacional: prefijo + dígitos sin ceros troncales.
fun fullPhone(dial: String, phone: String): String =
    dial.filter { it.isDigit() } + phone.filter { it.isDigit() }.dropWhile { it == '0' }

// Puras, testeables.
fun isPhoneOk(phone: String): Boolean = phone.filter { it.isDigit() }.length >= 6

fun isCodeOk(code: String): Boolean = code.length == 5 && code != "00000"
