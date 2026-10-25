package com.nimbo.feature.main

import kotlinx.serialization.Serializable

// Destinos tipados (ADR 0007). Pestañas abajo; visores a pantalla completa
// (sin navbar) encima en la pila: back los cierra sin perder la carpeta.
@Serializable data object FilesRoute
@Serializable data object MusicRoute
@Serializable data object PhotosRoute
@Serializable data object MoreRoute

// Galería: los ids van en orden (la lista que se veía al abrirla).
@Serializable data class PhotoRoute(val ids: List<Long>, val index: Int)
@Serializable data class VideoRoute(val messageId: Long)
@Serializable data class ReaderRoute(val messageId: Long)
