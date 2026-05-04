package com.github.jing332.common.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipboardManager.OnPrimaryClipChangedListener
import android.content.Context
import android.os.TransactionTooLargeException
import splitties.init.appCtx

/**
 * Utils about clipboard.
 */
@Suppress("unused")
object ClipboardUtils {
    // Binder/剪贴板大文本容易崩，512KB 比较稳。
    private const val MAX_CLIPBOARD_BYTES = 512 * 1024

    /**
     * Copy the text to clipboard.
     *
     * The label equals name of package.
     *
     * @param text The text.
     * @return true = 复制成功；false = 内容过大或复制失败。
     */
    fun copyText(text: CharSequence?): Boolean {
        return copyText(appCtx.packageName, text)
    }

    /**
     * Copy the text to clipboard.
     *
     * @param label The label.
     * @param text  The text.
     * @return true = 复制成功；false = 内容过大或复制失败。
     */
    fun copyText(label: CharSequence?, text: CharSequence?): Boolean {
        val rawText = text?.toString().orEmpty()
        val byteSize = rawText.toByteArray(Charsets.UTF_8).size

        if (byteSize > MAX_CLIPBOARD_BYTES) {
            appCtx.toast("内容过大，不能复制到剪贴板，请使用导出文件")
            return false
        }

        return try {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label ?: appCtx.packageName, rawText))
            true
        } catch (e: TransactionTooLargeException) {
            appCtx.toast("内容过大，复制失败，请使用导出文件")
            false
        } catch (e: RuntimeException) {
            // 部分 ROM 会把 TransactionTooLargeException 包成 RuntimeException 抛出。
            if (e.hasCause<TransactionTooLargeException>()) {
                appCtx.toast("内容过大，复制失败，请使用导出文件")
            } else {
                appCtx.toast("复制失败：${e.message ?: e.javaClass.simpleName}")
            }
            false
        } catch (e: Throwable) {
            appCtx.toast("复制失败：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Clear the clipboard.
     */
    fun clear() {
        runCatching {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(null, ""))
        }
    }

    /**
     * Return the label for clipboard.
     *
     * @return the label for clipboard
     */
    fun getLabel(): CharSequence {
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val des = cm.primaryClipDescription ?: return ""
        return des.label ?: return ""
    }

    /**
     * Return the text for clipboard.
     *
     * @return the text for clipboard
     */
    val text: CharSequence
        get() {
            val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(appCtx)
                if (text != null) {
                    return text
                }
            }
            return ""
        }

    /**
     * Add the clipboard changed listener.
     */
    fun addChangedListener(listener: OnPrimaryClipChangedListener?) {
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener(listener)
    }

    /**
     * Remove the clipboard changed listener.
     */
    fun removeChangedListener(listener: OnPrimaryClipChangedListener?) {
        val cm = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.removePrimaryClipChangedListener(listener)
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }
}