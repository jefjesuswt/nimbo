package com.nimbo.ui.telegram

// Build personal: ML Kit en el teléfono (por defecto) + el traductor web de Google.
fun flavorTranslators(): List<Translator> = listOf(MlKitTranslator, GoogleWebTranslator)
