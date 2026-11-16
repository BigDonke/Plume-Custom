package dev.plume

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

/**
 * Videos stay in Plume's wallpaper folder; no player runs after the home screen stops.
 *
 * The player draws into [LiquidVideo], which keeps the last frame when the player stops. The next player picks up at
 * the same moment, so coming back to the home screen shows no jump.
 *
 * A video loops with a crossfade. A few seconds before the end a second player gets ready on the other input of
 * [LiquidVideo]. It starts [LOOP_FADE_MS] before the end, and from its first frame the picture fades over to it
 * while the old loop runs out. If the second player fails, the video starts over with a cut.
 */
internal class VideoWallpaper(
    private val liquid: LiquidVideo,
    private val onFrame: () -> Unit,
    private val onError: () -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null // on screen, drawing into input [slot]
    private var next: MediaPlayer? = null // the next loop, on the other input
    private var nextReady = false
    private var nextStarted = false
    private var nextFailed = false // no second player this loop: it starts over with a cut
    private var leaving: MediaPlayer? = null // the old loop, running out under the fade
    private var slot = 0
    private var file: File? = null
    private var active = false
    private var rendered = false // the player has shown a frame of the current file
    private var position = 0 // where the last player of this file stopped, in ms
    private val watch = Runnable { watchLoop() }
    private val dropLeaving = Runnable { leaving?.release(); leaving = null }

    fun show(file: File, play: Boolean) {
        if (this.file != file) { release(); this.file = file; position = 0 }
        else if (play && rendered) onFrame() // same video, already on screen: the caller covered it with the picture again
        active = play
        liquid.view.visibility = android.view.View.VISIBLE
        if (active) start()
    }

    fun pause() {
        active = false
        release()
    }

    fun resume() {
        active = true
        start()
    }

    fun clear() {
        pause()
        file = null
        position = 0
        liquid.view.visibility = android.view.View.GONE
    }

    private fun start() {
        // onResume can come before the wallpaper has loaded. With no file yet, show() starts the player later.
        val target = file ?: return
        if (!target.isFile) return onError()
        if (player != null) return
        try {
            player = newPlayer(target, slot)
        } catch (_: Exception) {
            release()
            onError()
        }
    }

    /** A player for [target] on input [on]. Its listeners check which role it has now, as the roles move on. */
    private fun newPlayer(target: File, on: Int) = MediaPlayer().also { p -> try {
        p.setOnPreparedListener {
            it.setVolume(0f, 0f)
            if (it === next) { nextReady = true; watchSoon(); return@setOnPreparedListener }
            it.isLooping = it.duration < LOOP_FADE_MS * 3 // too short to fade
            if (position in 1 until it.duration) it.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
            it.start()
            watchSoon()
        }
        p.setOnInfoListener { it, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                if (it === next) takeOver()
                else if (it === player && !rendered) { rendered = true; onFrame() }
            }
            false
        }
        p.setOnErrorListener { it, _, _ ->
            when {
                it === player -> { release(); onError() }
                it === next -> { dropNext(); nextFailed = true }
                it === leaving -> dropLeaving.run()
            }
            true
        }
        p.setOnCompletionListener {
            when {
                it === leaving -> dropLeaving.run()
                it !== player -> {}
                nextStarted -> takeOver() // its first frame never reported: fade now
                else -> { it.seekTo(0); it.start(); nextFailed = false; watchSoon() }
            }
        }
        p.setDataSource(target.path)
        p.setSurface(liquid.inputs[on])
        p.prepareAsync()
    } catch (e: Exception) { p.release(); throw e } }

    private fun watchSoon() {
        ui.removeCallbacks(watch)
        ui.post(watch)
    }

    /** Gets the next loop ready near the end and starts it [LOOP_FADE_MS] before the end. */
    private fun watchLoop() {
        val p = player ?: return
        if (p.isLooping || nextStarted || !active) return
        val left = runCatching { if (p.isPlaying) p.duration - p.currentPosition else null }.getOrNull() ?: return
        if (next == null && !nextFailed && left <= LOOP_FADE_MS + LOOP_PREPARE_MS) {
            val target = file ?: return
            try { next = newPlayer(target, 1 - slot) } catch (_: Exception) { dropNext(); nextFailed = true }
        }
        val n = next
        if (n != null && nextReady && left <= LOOP_FADE_MS) {
            n.start()
            nextStarted = true
            return
        }
        val at = if (next == null && !nextFailed) LOOP_FADE_MS + LOOP_PREPARE_MS else LOOP_FADE_MS
        ui.postDelayed(watch, (left - at).coerceIn(50, 1000))
    }

    /** The next loop shows its first frame: it fades in on its input, and the old loop runs out under it. */
    private fun takeOver() {
        val n = next ?: return
        dropLeaving.run()
        leaving = player
        player = n
        next = null
        nextReady = false
        nextStarted = false
        nextFailed = false
        slot = 1 - slot
        rendered = true
        liquid.show(slot, LOOP_FADE_MS)
        ui.removeCallbacks(dropLeaving)
        ui.postDelayed(dropLeaving, LOOP_FADE_MS + 500)
        watchSoon()
    }

    private fun dropNext() {
        next?.release()
        next = null
        nextReady = false
        nextStarted = false
    }

    private fun release() {
        ui.removeCallbacks(watch)
        ui.removeCallbacks(dropLeaving)
        if (leaving != null) liquid.show(slot, 0) // a fade cut short ends on the new loop
        player?.let { p ->
            if (rendered) position = runCatching { p.currentPosition }.getOrDefault(0)
            p.release()
        }
        player = null
        rendered = false
        dropNext()
        nextFailed = false
        dropLeaving.run()
    }
}

/** How long a loop's end fades into its start, in ms. */
internal const val LOOP_FADE_MS = 4000L

/** How long before the fade the next loop's player gets ready, in ms. */
private const val LOOP_PREPARE_MS = 3000L

/** Reject mislabeled files and videos outside the TV wallpaper size limit. */
internal fun validVideo(file: File): Boolean {
    if (!file.isFile || file.length() !in 1..VIDEO_LIMIT) return false
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        (0 until extractor.trackCount).any { index ->
            val format = extractor.getTrackFormat(index)
            format.getString(android.media.MediaFormat.KEY_MIME) in setOf("video/avc", "video/hevc") &&
                format.getInteger(android.media.MediaFormat.KEY_WIDTH) <= 1920 &&
                format.getInteger(android.media.MediaFormat.KEY_HEIGHT) <= 1080
        }
    } catch (_: Exception) { false } finally { extractor.release() }
}

internal const val VIDEO_LIMIT = 350L * 1024 * 1024

internal data class VideoScene(val source: String, val category: String, val name: String, val file: String, val url: String)

private var cachedScenes: List<VideoScene>? = null

internal fun parseVideoScenes(input: InputStream): List<VideoScene> {
    val list = mutableListOf<VideoScene>()
    GZIPInputStream(input).bufferedReader(Charsets.UTF_8).useLines { lines ->
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size >= 5) {
                list.add(VideoScene(parts[0], parts[1], parts[2], parts[3], parts[4]))
            }
        }
    }
    return list
}

internal fun videoScenes(context: Context): List<VideoScene> {
    cachedScenes?.let { return it }
    val list = try {
        context.resources.openRawResource(R.raw.video_scenes).use { parseVideoScenes(it) }
    } catch (_: Exception) { emptyList() }
    cachedScenes = list
    return list
}

/** Download on selection only. A partial or oversized file never replaces a saved video. */
internal fun downloadVideoScene(item: VideoScene, destination: File, cancelled: AtomicBoolean, progress: (Int) -> Unit): Boolean {
    val part = File(destination.parentFile, destination.name + ".part")
    var connection: HttpURLConnection? = null
    return try {
        connection = (URL(item.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }
        if (connection.responseCode != 200 || connection.contentLengthLong !in 1..VIDEO_LIMIT) return false
        val needed = connection.contentLengthLong + 20L * 1024 * 1024
        if (destination.parentFile?.let { it.usableSpace < needed } == true) return false
        var count = 0L
        var lastPercent = -1
        connection.inputStream.use { input ->
            part.outputStream().use { output ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled.get()) return false
                    val n = input.read(bytes)
                    if (n < 0) break
                    count += n
                    if (count > VIDEO_LIMIT) return false
                    output.write(bytes, 0, n)
                    val percent = (count * 100 / connection.contentLengthLong).toInt()
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            }
        }
        if (cancelled.get() || !validVideo(part)) false else part.renameTo(destination)
    } catch (_: IOException) { false } finally { connection?.disconnect(); part.delete() }
}
