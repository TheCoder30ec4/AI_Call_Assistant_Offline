package app.offlinevoice.callprobe

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors

/** State shared by the screen and the call service: helper connection, arming and the report. */
object Probe {
    private const val TAG = "CallProbe"
    private const val PERMISSION_REQUEST = 4101

    private lateinit var app: Context
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    val worker = Executors.newSingleThreadExecutor()

    @Volatile var shell: IShellProbe? = null
        private set
    @Volatile var shizukuDetail = "Shizuku not running"
        private set
    @Volatile var onShellLost: (() -> Unit)? = null

    /** Set by the call service; invoked by the AI to end the current call (caller said goodbye). */
    @Volatile var onHangUp: (() -> Unit)? = null

    fun hangUp() {
        runCatching { onHangUp?.invoke() }.onFailure { log("hang up failed: ${it.message}") }
    }

    private val args by lazy {
        val updated = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellAudioService::class.java.name))
            .daemon(false) // The helper dies with the app, so a crash can't leave call audio redirected.
            .processNameSuffix("shell")
            .debuggable(false)
            .tag("callprobe")
            .version((updated / 1000 % Int.MAX_VALUE).toInt()) // New APK, new helper.
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            val service = binder?.takeIf { it.pingBinder() }?.let { IShellProbe.Stub.asInterface(it) }
            val uid = runCatching { service?.uid }.getOrNull()
            if (uid != 2000) {
                shizukuDetail = "Refused helper with uid $uid. This test needs Shizuku running through ADB, not root"
                log("helper refused uid=$uid")
                return
            }
            val connected = service!!
            shell = connected
            shizukuDetail = "Helper connected, uid $uid"
            log("helper connected uid=$uid")
            // Call access (appop + companion override) resets on reboot, so re-apply it on every
            // connect. Without it Telecom never offers calls to our service.
            worker.execute {
                runCatching { log("call access: " + connected.setup()) }
                // First run after push_models.sh: bring the models in, then load the AI.
                if (!modelsInstalled()) {
                    runCatching { if (importModels(connected)) loadAi() }
                        .onFailure { aiDetail = "Model import failed: ${it.message}"; log(aiDetail) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            shell = null
            shizukuDetail = "Helper disconnected"
            log("helper disconnected")
            onShellLost?.invoke()
            main.post { connect() }
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { connect() }
        Shizuku.addBinderDeadListener {
            shell = null
            shizukuDetail = "Shizuku stopped"
            onShellLost?.invoke()
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == PERMISSION_REQUEST) {
                if (result == PackageManager.PERMISSION_GRANTED) connect() else shizukuDetail = "Denied in Shizuku"
            }
        }
    }

    fun requestShizuku() {
        when {
            !Shizuku.pingBinder() -> shizukuDetail = "Start Shizuku first"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> connect()
            else -> Shizuku.requestPermission(PERMISSION_REQUEST)
        }
    }

    private fun connect() {
        if (shell != null) return
        runCatching {
            if (!Shizuku.pingBinder()) {
                shizukuDetail = "Start Shizuku first"
                return
            }
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                shizukuDetail = "Shizuku v13 or newer is required"
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                shizukuDetail = "Grant access in Shizuku"
                return
            }
            shizukuDetail = "Connecting helper"
            Shizuku.bindUserService(args, connection)
        }.onFailure { shizukuDetail = "Shizuku error: ${it.message}" }
    }

    /** Waits briefly for the helper, because the app process may have started just for this call. */
    fun whenConnected(timeoutMs: Long, callback: (IShellProbe?) -> Unit) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        fun check() {
            val current = shell
            when {
                current != null -> callback(current)
                SystemClock.elapsedRealtime() >= deadline -> callback(null)
                else -> main.postDelayed({ check() }, 100)
            }
        }
        check()
    }

    private fun prefs() = app.getSharedPreferences("probe", Context.MODE_PRIVATE)

    var ownerName: String
        get() = prefs().getString("ownerName", "").orEmpty()
        set(value) = prefs().edit().putString("ownerName", value).apply()

    /** The master switch: the AI answers unanswered calls only while this is on. */
    var enabled: Boolean
        get() = prefs().getBoolean("enabled", false)
        set(value) = prefs().edit().putBoolean("enabled", value).apply()

    /** Seconds a call rings before the AI picks up. One of 10, 15, 20. */
    var ringDelaySeconds: Int
        get() = prefs().getInt("ringDelaySeconds", 15)
        set(value) = prefs().edit().putInt("ringDelaySeconds", value).apply()

    val ringDelayMs: Long get() = ringDelaySeconds * 1000L

    /** Kept for callers that referenced the old constant; now reflects the chosen delay. */
    @Deprecated("use ringDelayMs") val RING_DELAY_MS: Long get() = ringDelayMs

    /** Whether onboarding has been completed, so the app opens straight to the dashboard after. */
    var onboarded: Boolean
        get() = prefs().getBoolean("onboarded", false)
        set(value) = prefs().edit().putBoolean("onboarded", value).apply()

    private val report get() = File(app.filesDir, "report.jsonl")

    /** Measurements and events only. Never audio, and never the number of a real caller. */
    @Synchronized
    fun log(event: String) {
        Log.i(TAG, event)
        val line = JSONObject().put("time", System.currentTimeMillis()).put("event", event).toString()
        report.appendText(line + "\n")
    }

    fun recentLog(lines: Int): String =
        if (report.exists()) report.readLines().takeLast(lines).joinToString("\n") else "(empty)"

    /** Google's on-device recognizer, shared by the AI and the language-pack check. */
    val google by lazy { GoogleAsr(app) }

    /** Last language-pack status, e.g. {en-IN=installed, hi-IN=downloading}. Shown in Settings. */
    @Volatile var speechPacks: Map<String, String> = emptyMap()

    /** Checks the Indian English, Hindi and Telugu packs, and downloads any that are missing. */
    fun prepareSpeechPacks() {
        if (!google.available) {
            speechPacks = GoogleAsr.LANGUAGES.associateWith { "on-device recognizer unavailable" }
            return
        }
        google.prepareLanguages(GoogleAsr.LANGUAGES) { status ->
            speechPacks = status
            log("speech packs: $status")
        }
    }

    fun contactName(number: String): String? = Contacts.nameFor(app, number)

    fun contactBias(): List<String> = Contacts.biasNames(app)

    /** Null until the models have loaded. */
    @Volatile var agent: AiAgent? = null
        private set
    @Volatile var aiDetail = "AI not loaded"
        private set
    private val aiWaiting = mutableListOf<(AiAgent) -> Unit>()
    private var aiLoading = false

    /** Where the AI models live: the app's private storage. */
    private fun modelDir() = File(app.filesDir, "models")

    fun modelsInstalled() = AiAgent.REQUIRED_FILES.all { File(modelDir(), it).exists() }

    /**
     * push_models.sh leaves the models in /data/local/tmp, which only the shell user can read, so the
     * helper hands each file over and it's copied into private storage. Returns true if any were.
     */
    private fun importModels(shell: IShellProbe): Boolean {
        val files = shell.listModelImport().lines().filter(String::isNotBlank)
        if (files.isEmpty()) return false
        val root = modelDir().canonicalFile
        files.forEachIndexed { index, path ->
            aiDetail = "Importing AI models (${index + 1} of ${files.size})"
            val target = File(root, path).canonicalFile
            require(target.path.startsWith(root.path + File.separator)) { "bad model path $path" }
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell.openModelImport(path)).use { input ->
                partial.outputStream().use { input.copyTo(it, 1 shl 20) }
            }
            check(partial.renameTo(target)) { "couldn't save $path" }
        }
        shell.clearModelImport() // Frees the second copy.
        log("imported ${files.size} model files")
        return true
    }

    /** Loads the models once, off the audio path. Runs [then] with the agent when ready. */
    fun loadAi(then: (AiAgent) -> Unit = {}) {
        synchronized(aiWaiting) {
            agent?.let {
                worker.execute { then(it) }
                return
            }
            aiWaiting += then
            // A second load at the same time would stall, so later requests just wait for this one.
            if (aiLoading) return
            aiLoading = true
        }
        aiDetail = "Loading AI models"
        Thread({
            val result = runCatching {
                AiAgent(modelDir(), app.cacheDir, ownerName, google).also { it.load() }
            }
            val waiting = synchronized(aiWaiting) {
                aiLoading = false
                agent = result.getOrNull()
                aiWaiting.toList().also { aiWaiting.clear() }
            }
            result.onSuccess { loaded ->
                aiDetail = "AI ready"
                worker.execute { waiting.forEach { it(loaded) } }
            }.onFailure {
                aiDetail = if (!modelsInstalled()) "AI models not installed yet. Run push_models.sh (see README)."
                    else "AI failed to load: ${it.message}"
                log(aiDetail)
            }
        }, "ai-load").start()
    }

    /** Answered calls, newest first. Private storage only, never in logcat or the report. */
    private val callsDir get() = File(app.filesDir, "calls")

    fun newCall(label: String): CallRecord = CallLog.newCall(callsDir, label)

    fun recentCalls(limit: Int): List<CallRecord> = CallLog.recent(callsDir, limit)

    /** Posts a notification with the finished summary, so a handled call is seen without opening the app. */
    fun notifySummary(call: CallRecord) {
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel("summaries", "Call summaries", android.app.NotificationManager.IMPORTANCE_HIGH),
        )
        // Tapping it opens this call's summary.
        val open = android.app.PendingIntent.getActivity(
            app, (call.startedAt % 100000).toInt(),
            android.content.Intent(app, CallDetailActivity::class.java)
                .putExtra("dir", call.dir.absolutePath)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val memo = call.memo
        val urgency = call.urgency
        val from = memo.caller?.let { " from $it" }.orEmpty()
        val title = if (urgency == Urgency.HIGH) "Urgent call$from" else "Missed call$from"
        val brief = memo.reason ?: memo.message ?: "The AI answered and took a message."
        val details = listOfNotNull(memo.message?.takeIf { it != brief }, memo.callback?.let { "Call back: $it" })
        val notification = android.app.Notification.Builder(app, "summaries")
            .setContentTitle(title)
            .setSubText(urgency.label)
            .setContentText(brief)
            .setStyle(android.app.Notification.BigTextStyle().bigText((listOf(brief) + details).joinToString("\n")))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(urgency.color) // Tints the icon/accent: red urgent, blue normal, green low.
            .setColorized(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // A fixed per-call id (truncated timestamp) so each call gets its own notification.
        runCatching { manager.notify((call.startedAt % 100000).toInt() + 100, notification) }
    }

    /** Spoken only if the AI isn't loaded when a call is answered, so the caller still hears the disclosure. */
    fun disclosure(): ByteArray = app.assets.open("disclosure_16k.pcm").use { it.readBytes() }

    /** Deletes all answered-call records (summaries, transcripts, recordings). Returns how many. */
    fun deleteCalls(): Int = recentCalls(Int.MAX_VALUE).count { it.dir.deleteRecursively() }
}
