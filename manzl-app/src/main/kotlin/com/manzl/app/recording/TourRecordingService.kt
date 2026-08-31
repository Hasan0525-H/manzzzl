package com.manzl.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Local-only screen recorder for walkthrough sessions.
 *
 * MediaProjection -> MediaRecorder -> H.264/MP4. The recording is never uploaded. On stop, the
 * temporary MP4 is copied to Movies/Manzl through MediaStore.
 */
class TourRecordingService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private val stopping = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopping.get()) stopCapture(save = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture(save = true)
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (recorder != null) return
        stopping.set(false)
        startForeground(NOTIFICATION_ID, notification("جارٍ تسجيل جولة منزل"))

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val requestedWidth = intent.getIntExtra(EXTRA_WIDTH, 1080)
        val requestedHeight = intent.getIntExtra(EXTRA_HEIGHT, 1920)
        val densityDpi = intent.getIntExtra(EXTRA_DENSITY_DPI, 420)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopCapture(save = false)
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        mediaProjection = projection

        val (width, height) = normalizedVideoSize(requestedWidth, requestedHeight)
        val output = File(cacheDir, "manzl-tour-${System.currentTimeMillis()}.mp4")
        tempFile = output

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        runCatching {
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(VIDEO_FPS)
            mediaRecorder.setVideoEncodingBitRate(videoBitrate(width, height))
            mediaRecorder.setOutputFile(output.absolutePath)
            mediaRecorder.prepare()

            virtualDisplay = projection.createVirtualDisplay(
                "ManzlTourRecording",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null,
                null,
            )
            mediaRecorder.start()
            recorder = mediaRecorder
            TourRecordingState.setRecording(true)
        }.onFailure {
            runCatching { mediaRecorder.release() }
            recorder = null
            stopCapture(save = false)
        }
    }

    private fun stopCapture(save: Boolean) {
        if (!stopping.compareAndSet(false, true)) return
        val output = tempFile
        val activeRecorder = recorder
        recorder = null

        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
            runCatching { activeRecorder.reset() }
            runCatching { activeRecorder.release() }
        }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        mediaProjection?.let { projection ->
            runCatching { projection.unregisterCallback(projectionCallback) }
            runCatching { projection.stop() }
        }
        mediaProjection = null

        val savedUri = if (save && output != null && output.exists() && output.length() > MIN_VALID_MP4_BYTES) {
            saveToMediaStore(output)
        } else {
            null
        }
        runCatching { output?.delete() }
        tempFile = null

        TourRecordingState.finish(savedUri?.toString())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveToMediaStore(source: File): android.net.Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Manzl-${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Manzl")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = contentResolver.insert(collection, values) ?: return null
        return runCatching {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output, bufferSize = 1024 * 1024) }
            } ?: error("تعذر فتح ملف الفيديو للحفظ")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        }.getOrElse {
            runCatching { contentResolver.delete(uri, null, null) }
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "تسجيل جولة منزل",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "إشعار يظهر فقط أثناء تسجيل الجولة"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("منزل")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun normalizedVideoSize(width: Int, height: Int): Pair<Int, Int> {
        var w = width.coerceAtLeast(320)
        var h = height.coerceAtLeast(320)
        val longest = maxOf(w, h)
        if (longest > MAX_VIDEO_LONG_SIDE) {
            val scale = MAX_VIDEO_LONG_SIDE.toFloat() / longest.toFloat()
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        w -= w % 2
        h -= h % 2
        return w.coerceAtLeast(320) to h.coerceAtLeast(320)
    }

    private fun videoBitrate(width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val scaled = (pixels * VIDEO_FPS * BITS_PER_PIXEL_FRAME).toLong()
        return min(MAX_VIDEO_BITRATE.toLong(), scaled).coerceAtLeast(MIN_VIDEO_BITRATE.toLong()).toInt()
    }

    override fun onDestroy() {
        if (recorder != null || mediaProjection != null) stopCapture(save = true)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.manzl.app.recording.START"
        private const val ACTION_STOP = "com.manzl.app.recording.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_WIDTH = "width"
        private const val EXTRA_HEIGHT = "height"
        private const val EXTRA_DENSITY_DPI = "density_dpi"

        private const val CHANNEL_ID = "manzl_tour_recording"
        private const val NOTIFICATION_ID = 4701
        private const val VIDEO_FPS = 30
        private const val MAX_VIDEO_LONG_SIDE = 1920
        private const val MIN_VIDEO_BITRATE = 4_000_000
        private const val MAX_VIDEO_BITRATE = 14_000_000
        private const val BITS_PER_PIXEL_FRAME = 0.14f
        private const val MIN_VALID_MP4_BYTES = 16_384L

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            width: Int,
            height: Int,
            densityDpi: Int,
        ) {
            val intent = Intent(context, TourRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DENSITY_DPI, densityDpi)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TourRecordingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
