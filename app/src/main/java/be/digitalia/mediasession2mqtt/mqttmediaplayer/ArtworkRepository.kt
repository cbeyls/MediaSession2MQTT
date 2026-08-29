package be.digitalia.mediasession2mqtt.mqttmediaplayer

import android.content.Context
import android.net.http.HttpResponseCache
import be.digitalia.mediasession2mqtt.mediasession.CurrentMediaControllerDetector
import be.digitalia.mediasession2mqtt.mediasession.metadataFlow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.io.IOException

private data class ArtworkRequest(
    val source: ArtworkSource,
    val cacheKey: String
)

@Inject
@SingleIn(AppScope::class)
class ArtworkRepository(
    context: Context,
    currentMediaControllerDetector: CurrentMediaControllerDetector
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        installHttpCache(context)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val artwork: StateFlow<ByteArray?> =
        currentMediaControllerDetector.currentMediaController.flatMapLatest { mediaController ->
            when (mediaController) {
                null -> flowOf(
                    ArtworkRequest(
                        source = ArtworkSource.None,
                        cacheKey = ""
                    )
                )
                else -> mediaController.metadataFlow.map { metadata ->
                    ArtworkRequest(
                        source = metadata.toArtworkSource(),
                        cacheKey = metadata.toArtworkCacheKey(mediaController.packageName)
                    )
                }
            }
        }
            .distinctUntilChanged()
            .mapLatest { request -> resolveArtworkPng(request.source) }
            .buffer(Channel.RENDEZVOUS)
            .distinctUntilChanged { previous, current -> previous.contentEquals(current) }
            .stateIn(
                scope = coroutineScope,
                started = SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = ARTWORK_SHARING_STOP_TIMEOUT_MILLIS,
                    replayExpirationMillis = 0L
                ),
                initialValue = null
            )

    companion object {
        private const val ARTWORK_SHARING_STOP_TIMEOUT_MILLIS = 5000L
        private const val HTTP_CACHE_MAX_BYTES = 20L * 1024L * 1024L
        private const val HTTP_CACHE_DIRECTORY = "artwork_http"

        private fun installHttpCache(context: Context) {
            synchronized(HttpResponseCache::class.java) {
                if (HttpResponseCache.getInstalled() == null) {
                    try {
                        HttpResponseCache.install(
                            File(context.cacheDir, HTTP_CACHE_DIRECTORY),
                            HTTP_CACHE_MAX_BYTES
                        )
                    } catch (_: IOException) {
                        // Artwork downloads still work when the optional cache is unavailable.
                    }
                }
            }
        }
    }
}
