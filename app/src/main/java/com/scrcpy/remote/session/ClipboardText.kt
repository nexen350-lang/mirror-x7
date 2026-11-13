package com.scrcpy.remote.session

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader

/**
 * Text for a clipboard item, resolved the way [ClipData.Item.coerceToText] resolves it, but bounded
 * and meant for a background thread.
 *
 * For an item that holds only a URI, the platform opens the provider's text stream and reads all
 * of it on the calling thread: a slow, hung or huge provider would freeze the mirror's main thread
 * and could exhaust memory before the protocol limit was ever applied.
 */
internal object ClipboardText {

    /**
     * Reads at most [maxChars] characters of [item]'s text. Call off the main thread.
     *
     * @throws IOException if the provider opened a text stream but reading it failed. The platform
     * pastes the exception's own text in that case; nothing is pasted here instead.
     */
    @Throws(IOException::class)
    fun read(context: Context, item: ClipData.Item, maxChars: Int): String {
        require(maxChars > 0)
        item.text?.let { return it.toString() }
        val uri = item.uri ?: return item.intent?.toUri(Intent.URI_INTENT_SCHEME).orEmpty()
        openText(context, uri)?.use { descriptor ->
            return InputStreamReader(descriptor.createInputStream(), Charsets.UTF_8).use { readAtMost(it, maxChars) }
        }
        // As the platform does: a stream that is not text is not pasted as its own address.
        return if (uri.scheme in LOCAL_SCHEMES) "" else uri.toString()
    }

    /** The provider's text stream for [uri], or null when it offers none. */
    private fun openText(context: Context, uri: Uri): AssetFileDescriptor? = try {
        context.contentResolver.openTypedAssetFileDescriptor(uri, "text/*", null)
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun readAtMost(reader: Reader, maxChars: Int): String {
        val text = StringBuilder()
        val buffer = CharArray(8 * 1024)
        while (text.length < maxChars) {
            val count = reader.read(buffer, 0, minOf(buffer.size, maxChars - text.length))
            if (count < 0) return text.toString()
            text.append(buffer, 0, count)
        }
        // Stopped at the bound, possibly between the halves of a surrogate pair.
        if (Character.isHighSurrogate(text[text.length - 1])) text.setLength(text.length - 1)
        return text.toString()
    }

    private val LOCAL_SCHEMES = setOf(
        ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE, ContentResolver.SCHEME_FILE,
    )
}
