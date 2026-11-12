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
import hu.edzes.model.ZoneCoach
import hu.edzes.model.hrHistogram
import hu.edzes.model.isCardio
import hu.edzes.model.targetZone
import hu.edzes.model.zoneOf
import hu.edzes.model.steps
import hu.edzes.prefs
import hu.edzes.data.Tracks
import hu.edzes.sync.Sync
import hu.edzes.sync.SyncWorker
import hu.edzes.sync.TrackPoint
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
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
    val totalDistM: Double = 0.0,
    val paceSPerKm: Int? = null,     // pillanatnyi tempó (az utolsó ~30 mp alapján)
    val heartRate: Int? = null,      // élő pulzus Bluetooth-ról
    val remainingS: Long? = null,    // hátralévő idő, ha minden hátralévő lépés időre megy
    val zone: Int? = null,           // a pillanatnyi pulzuszóna, ha van max pulzus beállítva
    val targetZone: Int? = null,     // az aktuális lépés (vagy az egész edzés) célzónája
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
    private var totalDistM = 0.0
    private val track = mutableListOf<TrackPoint>()
    private val recent = ArrayDeque<Pair<Long, Double>>()   // (edzésidő ms, össztáv m) a tempóhoz
    private var nextKm = 1
    private var hrMonitor: HeartRateMonitor? = null
    private var heartRate: Int? = null
    private var heartRateAtMs = 0L
    private var hrSum = 0L
    private var hrCount = 0
    private var lastHrPointMs = 0L
    private val hrSamples = mutableListOf<Pair<Long, Int>>()
    private val coach = ZoneCoach()
    private val maxHr get() = prefs.getInt("max_hr", 0).takeIf { it > 0 }

    /** Az aktuális lépés célzónája; szakaszok nélküli edzésnél az edzés céljából. Pihenőnél nincs. */
    private fun targetZone(): Int? = engine.current.let { step ->
        when {
            step == null -> targetZone(workout.target).takeIf { engine.steps.isEmpty() }
            step.rest -> null
            else -> targetZone(step.info)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - heartRateAtMs > 15_000) heartRate = null
            // GPS nélküli edzésnél (pl. konditerem) a pulzust 5 mp-enként külön pontként tesszük el.
            if (!gps && heartRate != null && !engine.paused && now - lastHrPointMs >= 5000) {
                lastHrPointMs = now
                track += TrackPoint(System.currentTimeMillis(), hr = heartRate)
            }
            handle(engine.tick(now - lastTick))
            lastTick = now
            handler.postDelayed(this, 200)
        }
    }

    private val locationListener = LocationListener { fix ->
        if (engine.paused) { lastFix = null; return@LocationListener }
        if (fix.accuracy > 20) return@LocationListener
        val prev = lastFix
        val d = prev?.distanceTo(fix) ?: 0f
        if (prev != null && d < 5) return@LocationListener
        lastFix = fix
        track += TrackPoint(fix.time, fix.latitude, fix.longitude, if (fix.hasAltitude()) fix.altitude else null, heartRate)
        if (prev == null) return@LocationListener
        totalDistM += d
        recent += engine.totalMs to totalDistM
        while (recent.size > 2 && engine.totalMs - recent.first().first > 30_000) recent.removeFirst()
        val kmStep = if (workout.sport == "bike") 5 else 1
        if (totalDistM >= nextKm * 1000) {
            val pace = (engine.totalMs / totalDistM).toInt()   // mp/km
            say("$nextKm kilométer, átlagtempó ${fmtSecs(pace)}", flush = false)
            nextKm += kmStep
        }
        handle(engine.addDistance(d.toDouble()))
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

        nextKm = if (w.sport == "bike") 5 else 1
        val wantsGps = (isCardio(w.sport) || engine.steps.any { it.distanceM != null }) &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val type = when {
            wantsGps -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        if (wantsGps) startGps()
        if (prefs.getBoolean("live_hr", true)) hrMonitor = HeartRateMonitor(this) { bpm ->
            heartRate = bpm
            heartRateAtMs = SystemClock.elapsedRealtime()
            if (!engine.paused) {
                hrSum += bpm; hrCount++
                hrSamples += System.currentTimeMillis() to bpm
                val max = maxHr
                if (max != null) coach.check(
                    SystemClock.elapsedRealtime(), if (engine.current == null) engine.totalMs else engine.stepMs, zoneOf(bpm, max), targetZone(),
                )?.let { say(it, flush = false) }
            }
        }.also { it.start() }

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
            is Engine.Event.Started -> { signal(long = true); announce(e.step) }
            is Engine.Event.Done -> when {
                // Kardió részidő: a szakasz tényleges ideje és (GPS-szel) távja.
                isCardio(workout.sport) -> if (!e.step.rest && e.ms >= 5000)
                    log += SetLog(activityId = 0, exercise = e.step.title, durationS = (e.ms / 1000).toInt(), distanceM = e.distM.takeIf { gps })
                // Időre végzett gyakorlat (pl. plank) naplózása, ha végigment.
                e.completed && e.step.exercise != null && e.step.durationS != null ->
                    log += SetLog(activityId = 0, exercise = e.step.exercise, durationS = e.step.durationS)
            }
            is Engine.Event.Countdown -> signal(long = false)
            Engine.Event.End -> { signal(long = true); say("Edzés vége") }
        }
        publish()
    }

    fun publish() {
        Player.state.value = PlayerState(
            workout, engine.index, engine.steps.size, engine.current, engine.next,
            engine.stepMs, engine.totalMs, engine.stepDistM, engine.paused, gps,
            totalDistM, pace(), heartRate, engine.remainingMs()?.let { (it + 999) / 1000 },
            heartRate?.let { hr -> maxHr?.let { zoneOf(hr, it) } }, targetZone(),
        )
        if (shownStep != engine.index) {
            shownStep = engine.index
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    private var shownStep = 0

    /** Pillanatnyi tempó mp/km-ben; null, ha nincs elég friss GPS adat (pl. állunk). */
    private fun pace(): Int? {
        if (recent.size < 2 || engine.totalMs - recent.last().first > 10_000) return null
        val meters = recent.last().second - recent.first().second
        val ms = recent.last().first - recent.first().first
        return if (meters < 10) null else (ms / meters).toInt()
    }

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
        val lastRound = LAST_ROUND.find(step.info)?.let { it.groupValues[1] == it.groupValues[2] && it.groupValues[2] != "1" } == true
        say(listOfNotNull(step.title, length, step.info.takeIf { !step.rest }, "utolsó kör".takeIf { lastRound && !step.rest }).joinToString(", "))
    }

    private fun say(text: String, flush: Boolean = true) {
        if (!ttsReady || !prefs.getBoolean("voice", true)) return
        val spoken = text.replace(Regex("\\bmp\\b"), "másodperc").replace("ism.", "ismétlés")
        tts?.speak(spoken, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "step")
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
        val distance = totalDistM.takeIf { it > 0 }
        val avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else null
        val points = track.toList()
        val hist = hrHistogram(hrSamples)
        if (save) ctx.app.scope.launch {
            val dao = ctx.dao
            val id = dao.insert(
                Activity(source = "app", sport = w.sport, title = w.title, startMs = start, durationS = durationS, distanceM = distance, avgHr = avgHr, hrHist = hist)
            )
            dao.insertSetLogs(logs.map { it.copy(activityId = id) })
            Tracks.write(ctx, id, points)
            pid?.let { dao.plannedById(it) }?.let { dao.update(it.copy(status = DONE, activityId = id)) }
            Sync.run(ctx)
            // Az óra pulzusadata később ér a Health Connectbe: fél óra múlva újra szinkronizálunk (ekkor megy fel a Stravára).
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "sync-after-workout", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(35, TimeUnit.MINUTES).build(),
            )
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        if (gps) getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        hrMonitor?.stop()
        tts?.shutdown()
        tone?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        if (Player.service === this) { Player.service = null; Player.state.value = null }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        private val LAST_ROUND = Regex("(\\d+)/(\\d+)\\. kör(?!.*kör)")
    }
}
