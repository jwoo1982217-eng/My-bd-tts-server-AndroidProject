package com.github.jing332.tts_server_android.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.github.jing332.tts_server_android.service.LegadoBridgeKeepAliveService
import com.github.jing332.tts_server_android.service.systts.help.AudiobookGenerationBridge

class LegadoBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle().apply {
            putBoolean("ok", false)
            putString("error", "context is null")
        }

        return when (method) {
            "start", "ping" -> {
                LegadoBridgeKeepAliveService.start(ctx)
                Bundle().apply {
                    putBoolean("ok", true)
                    putString("package", ctx.packageName)
                    putString("method", method)
                    putLong("time", System.currentTimeMillis())
                }
            }

            "stop" -> {
                LegadoBridgeKeepAliveService.stop(ctx)
                Bundle().apply {
                    putBoolean("ok", true)
                    putString("method", method)
                    putLong("time", System.currentTimeMillis())
                }
            }

            "prepareAudiobookGeneration" -> AudiobookGenerationBridge.prepare(ctx, extras)

            "appendAudiobookGenerationChapter" -> AudiobookGenerationBridge.appendChapter(arg, extras)

            "startAudiobookGeneration" -> AudiobookGenerationBridge.start(ctx, arg, extras)

            "submitAudiobookGeneration" -> AudiobookGenerationBridge.submit(ctx, extras)

            "queryAudiobookGeneration" -> AudiobookGenerationBridge.query(arg, extras)

            "cancelAudiobookGeneration" -> AudiobookGenerationBridge.cancel(arg, extras)

            else -> {
                Bundle().apply {
                    putBoolean("ok", false)
                    putString("error", "unknown method: $method")
                }
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("ok", "package", "time"))
        cursor.addRow(arrayOf<Any>(1, context?.packageName ?: "", System.currentTimeMillis()))
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.item/legado-tts-bridge"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
