package lab.mgb.findqueen.debug

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.scale
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One live-mode run (Start → Stop). Every line goes to logcat (stdout) with tag [DebugLog.TAG],
 * so a script on the computer the phone is plugged into can collect it with `adb logcat`:
 *
 *     SESSION,<id>,<key>=<value>        once per setting at Start
 *     HEADER,<id>,<csv header>          column names for FRAME lines
 *     FRAME,<id>,<DebugRecord csv>      one per checked frame
 *     EVENT,<id>,<t_ms>,<text>          e.g. beep, stop
 *     CRASH,<stack trace>               uncaught exceptions
 *
 * The same session is also written to filesDir/debug/<id>/ (session.txt, frames.csv and a
 * 240×320 thumbnail about once a second), which the script pulls with `adb exec-out run-as`
 * (debug builds only). Methods are synchronized: frames come from the camera thread,
 * events from the main thread.
 */
class DebugSession internal constructor(val id: String, private val dir: File?) : Closeable {
    private val startedAt = SystemClock.uptimeMillis()
    private val csv: BufferedWriter? = dir?.let { d ->
        runCatching { File(d, "frames.csv").bufferedWriter() }.getOrNull()
    }
    private var lastThumbAt = -THUMB_EVERY_MS
    private var thumbs = 0
    private var lastFlushAt = 0L
    private var closed = false

    init {
        DebugLog.out("HEADER,$id,${DebugRecord.CSV_HEADER}")
        writeFile(DebugRecord.CSV_HEADER)
    }

    /** Milliseconds since Start; use for [DebugRecord.tMs]. */
    fun elapsedMs(): Long = SystemClock.uptimeMillis() - startedAt

    /** Saves a thumbnail if one is due (about once a second) and returns its file name. */
    @Synchronized
    fun maybeThumb(frame: Bitmap): String? {
        val t = elapsedMs()
        if (closed || dir == null || thumbs >= MAX_THUMBS || t - lastThumbAt < THUMB_EVERY_MS) return null
        lastThumbAt = t
        thumbs++
        val name = "thumb_%09d.jpg".format(Locale.US, t)
        runCatching {
            val small = frame.scale(THUMB_WIDTH, THUMB_WIDTH * frame.height / frame.width)
            File(dir, name).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        }.onFailure { return null }
        return name
    }

    @Synchronized
    fun frame(record: DebugRecord) {
        if (closed) return
        val line = record.toCsvLine()
        DebugLog.out("FRAME,$id,$line")
        writeFile(line)
    }

    /** Free-form event, e.g. "beep". */
    @Synchronized
    fun event(text: String) {
        if (closed) return
        val clean = text.replace(',', ';')
        DebugLog.out("EVENT,$id,${elapsedMs()},$clean")
        writeFile("${elapsedMs()},,,,,,,,,EVENT:$clean,,,,,")
    }

    private fun writeFile(line: String) {
        val out = csv ?: return
        runCatching {
            out.write(line)
            out.newLine()
            // Flush about once a second so the file survives the app being killed.
            val now = SystemClock.uptimeMillis()
            if (now - lastFlushAt > 1_000) {
                out.flush()
                lastFlushAt = now
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        event("stop")
        closed = true
        runCatching { csv?.close() }
    }

    companion object {
        private const val THUMB_EVERY_MS = 1_000L
        private const val MAX_THUMBS = 600 // 10 minutes at one per second, ~7 MB
        private const val THUMB_WIDTH = 240
    }
}

/** Debug output for testing on the phone; see [DebugSession] for the line format. */
object DebugLog {
    /** Logcat tag; collect with `adb logcat -s FindQueen:I`. */
    const val TAG = "FindQueen"
    private const val KEEP_SESSIONS = 5
    private val stamp get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    internal fun out(line: String) {
        Log.i(TAG, line)
    }

    private fun root(context: Context) = File(context.filesDir, "debug").apply { mkdirs() }

    /** Starts a session: logs the settings and opens its folder (logging continues even if storage fails). */
    fun startSession(context: Context, settings: Map<String, Any?>): DebugSession {
        val id = "session_$stamp"
        val info = buildMap {
            put("started", Date().toString())
            put("phone", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("app", appVersion(context))
            putAll(settings.mapValues { it.value.toString() })
        }
        info.forEach { (k, v) -> out("SESSION,$id,$k=$v") }
        val dir = runCatching {
            prune(context)
            File(root(context), id).apply {
                mkdirs()
                File(this, "session.txt").writeText(info.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
            }
        }.getOrNull()
        return DebugSession(id, dir)
    }

    /** Logs uncaught exceptions (logcat + debug/crash_<time>.txt), then lets Android handle them as usual. */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = error.stackTraceToString()
                Log.e(TAG, "CRASH,thread=${thread.name},app=${appVersion(app)}\n$trace")
                File(root(app), "crash_$stamp.txt").writeText(
                    "thread=${thread.name}\nphone=${Build.MANUFACTURER} ${Build.MODEL}, android ${Build.VERSION.RELEASE}\n" +
                        "app=${appVersion(app)}\n$trace",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Keeps the newest sessions so stored logs never grow without limit. */
    private fun prune(context: Context) {
        val sessions = root(context).listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
            ?.sortedByDescending { it.name }.orEmpty()
        sessions.drop(KEEP_SESSIONS - 1).forEach { it.deleteRecursively() }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName} ($code)"
    }.getOrDefault("?")
}
