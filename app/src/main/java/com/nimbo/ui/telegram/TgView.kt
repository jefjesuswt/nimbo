package com.nimbo.ui.telegram

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

// Visor sistema (galería/player): descarga a caché + FileProvider.
// Nube-first: caché temporal, nada en Downloads.
object TgView {
    suspend fun open(ctx: Context, f: TgFile): Boolean = withContext(Dispatchers.IO) {
        try {
            if (f.fileId == 0) return@withContext false
            val res = TgClient.sendAwait(TdApi.DownloadFile(f.fileId, 32, 0, 0, true))
            val path = (res as? TdApi.File)?.local?.path?.takeIf { it.isNotEmpty() }
                ?: return@withContext false
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", java.io.File(path))
            val mime = when (f.kind) {
                TgKind.IMAGE -> "image/*"
                TgKind.VIDEO -> "video/*"
                else -> "*/*"
            }
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(
                Intent.createChooser(i, f.showTitle())
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
