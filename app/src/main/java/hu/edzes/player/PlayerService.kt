package hu.edzes.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import hu.edzes.App
import hu.edzes.MainActivity
import hu.edzes.app
import hu.edzes.dao
import hu.edzes.data.Activity
import hu.edzes.data.DONE
import hu.edzes.data.SetLog
import hu.edzes.model.Step
import hu.edzes.model.Workout
import hu.edzes.model.fmtMeters
import hu.edzes.model.fmtSecs
import hu.edzes.model.isCardio
import hu.edzes.model.steps
import hu.edzes.prefs
import hu.edzes.sync.Sync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class PlayerState(
    val workout: Workout,
    val index: Int,
    val stepCount: Int,
    val step: Step?,
    val next: Step?,
    val stepMs: Long,
    val totalMs: Long,
    val stepDistM: Double,
    val paused: Boolean,
    val gps: Boolean,
)

/** A futó edzés elérése a felületről. Minden hívás a fő szálon történik. */
object Player {
    val state = MutableStateFlow<PlayerState?>(null)
    internal var pending: Pair<Workout, Long?>? = null
    internal var service: PlayerService? = null

    fun start(ctx: Context, workout: Workout, plannedId: Long?) {
        if (state.value != null) return
        pending = workout to plannedId
        ContextCompat.startForegroundService(ctx, Intent(ctx, PlayerService::class.java))
    }

    fun next() = service?.apply { handle(engine.next()) }
    fun prev() = service?.apply { handle(engine.prev()) }
    fun togglePause() = service?.apply { engine.paused = !engine.paused; publish() }
    fun append(steps: List<Step>) = service?.apply { handle(engine.append(steps)) }

    /** Súlyzós sorozat kész: naplózás, majd továbblépés. */
    fun completeSet(reps: Int?, weightKg: Double?) = service?.apply {
        engine.current?.exercise?.let { log += SetLog(activityId = 0, exercise = it, reps = reps ?: 0, weightKg = weightKg) }
        handle(engine.next())
    }

    fun finish(save: Boolean) = service?.finish(save)
}

class PlayerService : Service() {
    lateinit var engine: Engine
    val log = mutableListOf<SetLog>()
    private lateinit var workout: Workout
    private var plannedId: Long? = null
    private var startWallMs = 0L
    private var lastTick = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var tone: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastFix: Location? = null
    private var gps = false

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            handle(engine.tick(now - lastTick))
            lastTick = now
            handler.postDelayed(this, 200)
        }
    }

    private val locationListener = LocationListener { fix ->
        if (fix.accuracy > 30) return@LocationListener
        val prev = lastFix
        if (prev == null) { lastFix = fix; return@LocationListener }
        val d = prev.distanceTo(fix)
        if (d >= 5) { lastFix = fix; handle(engine.addDistance(d.toDouble())) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (w, pid) = Player.pending ?: run { if (!::engine.isInitialized) stopSelf(); return START_NOT_STICKY }
        Player.pending = null
        workout = w
        plannedId = pid
        engine = Engine(w.steps())
        startWallMs = System.currentTimeMillis()
        lastTick = SystemClock.elapsedRealtime()
        Player.service = this

        val wantsGps = engine.steps.any { it.distanceM != null } &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = when {
            wantsGps -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        if (wantsGps) startGps()

        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edzes:player")
            .apply { acquire(6 * 3600 * 1000L) }
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = tts?.setLanguage(Locale.forLanguageTag("hu-HU")) != TextToSpeech.LANG_NOT_SUPPORTED
                engine.current?.let(::announce)
            }
        }
        handler.post(ticker)
        publish()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        val lm = getSystemService(LocationManager::class.java)
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        gps = true
    }

    fun handle(events: List<Engine.Event>) {
        for (e in events) when (e) {
            is Engine.Event.Started -> { lastFix = null; signal(long = true); announce(e.step) }
            is Engine.Event.Done ->
                // Időre végzett gyakorlat (pl. plank) naplózása, ha végigment.
                if (e.completed && e.step.exercise != null && e.step.durationS != null)
                    log += SetLog(activityId = 0, exercise = e.step.exercise, durationS = e.step.durationS)
            is Engine.Event.Countdown -> signal(long = false)
            Engine.Event.End -> { signal(long = true); say("Edzés vége") }
        }
        publish()
    }

    fun publish() {
        Player.state.value = PlayerState(
            workout, engine.index, engine.steps.size, engine.current, engine.next,
            engine.stepMs, engine.totalMs, engine.stepDistM, engine.paused, gps,
        )
        if (shownStep != engine.index) {
            shownStep = engine.index
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private var shownStep = 0

    private fun notification() = NotificationCompat.Builder(this, App.CHANNEL)
        .setSmallIcon(hu.edzes.R.drawable.ic_notification)
        .setContentTitle(workout.title)
        .setContentText(engine.current?.let { "${it.title} ${it.info}" } ?: "Edzés folyamatban")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .build()

    private fun announce(step: Step) {
        val length = step.durationS?.let(::fmtSecs) ?: step.distanceM?.let(::fmtMeters)
        say(listOfNotNull(step.title, length, step.info.takeIf { !step.rest }).joinToString(", "))
    }

    private fun say(text: String) {
        if (ttsReady && prefs.getBoolean("voice", true))
            tts?.speak(text.replace("mp", "másodperc").replace("ism.", "ismétlés"), TextToSpeech.QUEUE_FLUSH, null, "step")
    }

    private fun signal(long: Boolean) {
        tone?.startTone(if (long) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, if (long) 400 else 150)
        if (long) getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun finish(save: Boolean) {
        val durationS = (engine.totalMs / 1000).toInt()
        val w = workout
        val pid = plannedId
        val start = startWallMs
        val logs = log.toList()
        val ctx = applicationContext
        if (save) ctx.app.scope.launch {
            val dao = ctx.dao
            // Kardiónál az óra rögzít (Health Connectből jön az edzés), itt csak a tervet pipáljuk ki.
            val activityId = if (isCardio(w.sport)) null else {
                val id = dao.insert(Activity(source = "app", sport = w.sport, title = w.title, startMs = start, durationS = durationS))
                dao.insertSetLogs(logs.map { it.copy(activityId = id) })
                id
            }
            pid?.let { dao.plannedById(it) }?.let { dao.update(it.copy(status = DONE, activityId = activityId)) }
            Sync.run(ctx)
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (gps) getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        tts?.shutdown()
        tone?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        if (Player.service === this) { Player.service = null; Player.state.value = null }
        super.onDestroy()
    }

    companion object { const val NOTIFICATION_ID = 1 }
}
