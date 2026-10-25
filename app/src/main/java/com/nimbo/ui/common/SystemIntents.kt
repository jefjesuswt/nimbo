package com.nimbo.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

private fun Context.fileUri(path: String): Uri =
    FileProvider.getUriForFile(this, "$packageName.files", java.io.File(path))

// Visor del sistema (galería/player) sobre la caché TDLib vía FileProvider.
fun openWithSystem(ctx: Context, path: String, mime: String, title: String): Boolean = runCatching {
    val i = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(ctx.fileUri(path), mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, title).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    true
}.getOrDefault(false)

fun shareImage(ctx: Context, path: String, name: String): Boolean = runCatching {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, ctx.fileUri(path))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(i, name).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    true
}.getOrDefault(false)

// Abre el mensaje en el Telegram oficial (deep link tg://). False si no
// hay app que lo atienda.
fun openInTelegram(ctx: Context, userId: Long, messageId: Long): Boolean = runCatching {
    if (userId == 0L) return@runCatching false
    val i = Intent(Intent.ACTION_VIEW, Uri.parse("tg://openmessage?user_id=$userId&message_id=$messageId"))
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    if (i.resolveActivity(ctx.packageManager) == null) return@runCatching false
    ctx.startActivity(i)
    true
}.getOrDefault(false)
