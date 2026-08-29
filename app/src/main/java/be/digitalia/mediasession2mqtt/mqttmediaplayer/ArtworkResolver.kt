package be.digitalia.mediasession2mqtt.mqttmediaplayer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

private const val MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024
private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 10_000

private val ARTWORK_BITMAP_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_DISPLAY_ICON,
    MediaMetadata.METADATA_KEY_ART,
    MediaMetadata.METADATA_KEY_ALBUM_ART
)

private val ARTWORK_URI_KEYS = arrayOf(
    MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    MediaMetadata.METADATA_KEY_ART_URI,
    MediaMetadata.METADATA_KEY_ALBUM_ART_URI
)

sealed interface ArtworkSource {
    data object None : ArtworkSource
    data class BitmapValue(val value: Bitmap) : ArtworkSource
    data class Url(val value: URI) : ArtworkSource
}

/**
 * Build a cheap key that changes whenever the current media changes.
 */
fun MediaMetadata?.toArtworkCacheKey(packageName: String): String {
    if (this == null) {
        return packageName
    }
    return listOf(
        packageName,
        getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
        getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
    ).joinToString("\u0000")
}

/**
 * Select the preferred artwork source.
 */
fun MediaMetadata?.toArtworkSource(): ArtworkSource {
    if (this == null) {
        return ArtworkSource.None
    }

    return ARTWORK_BITMAP_KEYS.firstNotNullOfOrNull { key ->
        getBitmap(key)?.let { ArtworkSource.BitmapValue(it) }
    } ?: ARTWORK_URI_KEYS.firstNotNullOfOrNull { key ->
        getString(key)?.let { value ->
            runCatching { URI(value) }.getOrNull()?.takeIf { uri ->
                uri.host != null && (
                    uri.scheme.equals("http", ignoreCase = true) ||
                        uri.scheme.equals("https", ignoreCase = true)
                    )
            }
        }?.let { ArtworkSource.Url(it) }
    } ?: ArtworkSource.None
}

/**
 * Resolve the selected MediaSession artwork and encode it as lossless PNG bytes.
 */
suspend fun resolveArtworkPng(source: ArtworkSource): ByteArray =
    withContext(Dispatchers.IO) {
        when (source) {
            ArtworkSource.None -> ByteArray(0)
            is ArtworkSource.BitmapValue -> source.value.toPng()
            is ArtworkSource.Url -> downloadBitmap(source.value)?.let { bitmap ->
                try {
                    bitmap.toPng()
                } finally {
                    bitmap.recycle()
                }
            } ?: ByteArray(0)
        }
    }

private suspend fun downloadBitmap(uri: URI): Bitmap? {
    val bytes = httpGet(uri) ?: return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private suspend fun httpGet(uri: URI): ByteArray? {
    val connection = try {
        uri.toURL().openConnection() as? HttpURLConnection
    } catch (_: IOException) {
        null
    } ?: return null

    return try {
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.useCaches = true
        connection.setRequestProperty("User-Agent", "MediaSession2MQTT")
        connection.connect()

        if (connection.responseCode !in 200..299 ||
            connection.contentLength > MAX_DOWNLOAD_BYTES
        ) {
            return null
        }

        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                total += count
                if (total > MAX_DOWNLOAD_BYTES) {
                    return null
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: IOException) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun Bitmap.toPng(): ByteArray =
    ByteArrayOutputStream().use { output ->
        if (compress(Bitmap.CompressFormat.PNG, 100, output)) {
            output.toByteArray()
        } else {
            ByteArray(0)
        }
    }
