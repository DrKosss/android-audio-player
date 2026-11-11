package com.saptac.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject
import kotlin.math.abs

class PlayerService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL = "saptac_playback"
        const val NOTIF_ID = 4101
        const val ACTION_PLAY = "com.saptac.PLAY"
        const val ACTION_TOGGLE = "com.saptac.TOGGLE"
        const val ACTION_PAUSE = "com.saptac.PAUSE"
        const val ACTION_RESUME = "com.saptac.RESUME"
        const val ACTION_SEEK = "com.saptac.SEEK"
        const val ACTION_BUMP = "com.saptac.BUMP"
        const val ACTION_NEXT = "com.saptac.NEXT"
        const val ACTION_PREV = "com.saptac.PREV"
        const val ACTION_RATE = "com.saptac.RATE"
        const val ACTION_TIMER_START = "com.saptac.TIMER_START"
        const val ACTION_TIMER_CANCEL = "com.saptac.TIMER_CANCEL"
        const val ACTION_TIMER_EXTEND = "com.saptac.TIMER_EXTEND"
        const val ACTION_SYNC = "com.saptac.SYNC"
        const val ACTION_STOP = "com.saptac.STOP"
        const val CUSTOM_EXTEND = "com.saptac.EXTEND"
        const val EXTRA_POS = "pos"
        const val EXTRA_DELTA = "delta"
        const val EXTRA_MIN = "min"
        const val EXTRA_RATE = "rate"

        fun send(ctx: Context, action: String, block: (Intent.() -> Unit)? = null) {
            val i = Intent(ctx, PlayerService::class.java).setAction(action)
            block?.invoke(i)
            ctx.startForegroundService(i)
        }
    }

    private var mp: MediaPlayer? = null
    private var prepared = false
    private var state: PlayerState? = null
    private var timer: TimerState? = null
    private var volume = 1f
    private var lastSaveAt = 0L
    private var lastVolumeLevel = -1
    private var lastShakeAt = 0L
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager
    private lateinit var session: MediaSession
    private lateinit var notifManager: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var sensorOn = false
    private var started = false

    private val ticker = object : Runnable {
        override fun run() {
            try { tick() } catch (e: Exception) { Bus.log("Greška u tickeru: ${e.javaClass.simpleName}") }
            handler.postDelayed(this, 500L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    pause()
                    Bus.log("Slušalice isključene: pauza")
                }
                Intent.ACTION_SCREEN_ON -> maybeExtend("screen")
                "android.media.VOLUME_CHANGED_ACTION" -> maybeExtend("volume")
                Intent.ACTION_HEADSET_PLUG -> {
                    val p = intent.getIntExtra("state", -1)
                    if (p == 0) { pause(); Bus.log("Slušalice isključene: pauza") }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        lastVolumeLevel = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { -1 }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.playback_channel),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Kontrole reprodukcije i tajmer"
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notifManager.createNotificationChannel(ch)
        }

        session = MediaSession(this, "Šaptač").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(sessionCallback)
            isActive = true
        }

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "saptac:playback")

        startForeground(NOTIF_ID, buildNotification())
        started = true
        handler.post(ticker)
        updateSession()
        Bus.log("Servis za reprodukciju pokrenut")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) startForeground(NOTIF_ID, buildNotification())
        when (intent?.action) {
            ACTION_PLAY -> Bus.playRequest?.let { play(it) }
            ACTION_TOGGLE -> toggle()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_SEEK -> seek(intent.getLongExtra(EXTRA_POS, 0L))
            ACTION_BUMP -> bump(intent.getLongExtra(EXTRA_DELTA, 0L))
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_RATE -> setRate(intent.getFloatExtra(EXTRA_RATE, 1f))
            ACTION_TIMER_START -> startTimer(intent.getIntExtra(EXTRA_MIN, 30))
            ACTION_TIMER_CANCEL -> cancelTimer()
            ACTION_TIMER_EXTEND -> extendTimer()
            ACTION_STOP -> stopPlayback()
            ACTION_SYNC -> publish()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* ------------------------------------------------------------ reprodukcija */
    private fun play(req: PlayRequest) {
        if (req.queue.isEmpty()) return
        val idx = req.index.coerceIn(0, req.queue.lastIndex)
        val item = req.queue[idx]
        state = PlayerState(
            kind = req.kind, groupKey = req.groupKey, groupTitle = req.groupTitle,
            sub = req.sub, queue = req.queue, index = idx, cover = coverIndex(req.groupKey),
            initial = req.groupTitle.take(1).uppercase(), durationMs = item.durationMs,
            positionMs = req.startMs, playing = false, rate = req.rate,
            shuffle = req.shuffle, repeat = req.repeat
        )
        Store.touchGroup(req.groupKey)
        Bus.night = false
        Bus.undo = null
        timer?.let { if (it.phase == TPhase.GRACE) timer = null }
        load(idx, req.startMs)
        Bus.log("Puštanje: ${req.groupTitle} · ${item.title}")
        publish()
    }

    private fun load(index: Int, startMs: Long) {
        val p = state ?: return
        if (index !in p.queue.indices) return
        releasePlayer()
        prepared = false
        val item = p.queue[index]
        state = p.copy(index = index, durationMs = item.durationMs, positionMs = startMs, playing = false)
        volume = if (timer?.phase == TPhase.GRACE) 0f else 1f
        try {
            val player = MediaPlayer()
            mp = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(this, Uri.parse(item.uri))
            player.setOnPreparedListener {
                prepared = true
                val dur = try { player.duration.toLong() } catch (_: Exception) { item.durationMs }
                state = state?.copy(durationMs = dur.coerceAtLeast(1L))
                try {
                    if (startMs > 0 && startMs < dur) player.seekTo(startMs.toInt())
                    applyRate()
                    player.setVolume(volume, volume)
                    requestFocus()
                    player.start()
                    state = state?.copy(playing = true, positionMs = startMs)
                    wakeLock?.let { if (!it.isHeld) it.acquire(6 * 60 * 60 * 1000L) }
                } catch (e: Exception) { Bus.log("Greška pri startu: ${e.message}") }
                savePosition(force = true)
                publish()
            }
            player.setOnCompletionListener { onCompleted() }
            player.setOnErrorListener { _, what, extra ->
                Bus.log("Greška plejera ($what/$extra): ${item.title}")
                Bus.showToast("Ne mogu da pustim ovaj fajl.")
                state = state?.copy(playing = false)
                publish()
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            Bus.log("Ne mogu da otvorim fajl: ${e.javaClass.simpleName} · ${item.title}")
            Bus.showToast("Ne mogu da otvorim: ${item.title}")
            state = state?.copy(playing = false)
        }
        publish()
    }

    private fun releasePlayer() {
        try { mp?.reset() } catch (_: Exception) { }
        try { mp?.release() } catch (_: Exception) { }
        mp = null
        prepared = false
    }

    private fun isPlaying(): Boolean = try { mp?.isPlaying == true } catch (_: Exception) { false }

    private fun toggle() {
        if (isPlaying()) pause() else resume()
    }

    private fun pause() {
        try { if (prepared && isPlaying()) mp?.pause() } catch (_: Exception) { }
        state = state?.copy(playing = false)
        savePosition(force = true)
        Bus.log("Pauza: ${state?.current?.title ?: "—"}")
        publish()
    }

    private fun resume() {
        val p = state ?: return
        if (mp == null || !prepared) { load(p.index, p.positionMs); return }
        if (timer?.phase == TPhase.GRACE) extendTimer()
        else {
            try {
                requestFocus()
                val t = timer
                volume = if (t != null && t.phase == TPhase.FADE)
                    (t.leftMs.toFloat() / t.fadeMs).coerceIn(0f, 1f) else 1f
                mp?.setVolume(volume, volume)
                mp?.start()
                applyRate()
                state = state?.copy(playing = true)
                wakeLock?.let { if (!it.isHeld) it.acquire(6 * 60 * 60 * 1000L) }
                Bus.log("Nastavak: ${p.current.title}")
            } catch (e: Exception) { Bus.log("Greška pri nastavku: ${e.message}") }
        }
        publish()
    }

    private fun seek(posMs: Long) {
        val p = state ?: return
        val dur = p.durationMs.coerceAtLeast(1)
        val target = posMs.coerceIn(0, dur)
        try { if (prepared) mp?.seekTo(target.toInt()) } catch (_: Exception) { }
        state = p.copy(positionMs = target)
        savePosition(force = true)
        publish()
    }

    private fun bump(deltaMs: Long) {
        val p = state ?: return
        seek(p.positionMs + deltaMs)
    }

    private fun next() {
        val p = state ?: return
        val nextIndex = when {
            p.kind == Kind.TRACK && p.shuffle && p.queue.size > 1 ->
                (0 until p.queue.size).filter { it != p.index }.randomOrNull() ?: 0
            p.index + 1 < p.queue.size -> p.index + 1
            p.kind == Kind.TRACK && p.repeat -> 0
            else -> -1
        }
        if (nextIndex < 0) {
            Bus.showToast("Kraj reda")
            pause()
            return
        }
        load(nextIndex, 0)
        Bus.log("Sledeće: ${p.queue[nextIndex].title}")
    }

    private fun prev() {
        val p = state ?: return
        if (p.positionMs > 3000) { seek(0); return }
        if (p.index > 0) load(p.index - 1, 0)
        else seek(0)
    }

    private fun setRate(rate: Float) {
        val p = state ?: return
        val r = if (p.kind == Kind.TRACK) 1f else rate.coerceIn(0.5f, 2f)
        state = p.copy(rate = r)
        applyRate()
        savePosition(force = true)
        Bus.log("Brzina: ${fmtRate(r)}")
        publish()
    }

    private fun applyRate() {
        val p = state ?: return
        if (!prepared) return
        try {
            val pp = PlaybackParams()
            pp.speed = p.rate
            pp.pitch = 1f
            mp?.playbackParams = pp
        } catch (e: Exception) { Bus.log("Brzina nije podržana: ${e.javaClass.simpleName}") }
    }

    private fun onCompleted() {
        val p = state ?: return
        Store.setDone(p.current.id, true)
        Store.setPosition(p.current.id, 0)
        Bus.log("Kraj fajla: ${p.current.title}")
        if (p.kind == Kind.EPISODE && !Store.settings.nextAuto) {
            state = p.copy(playing = false, positionMs = 0)
            Bus.showToast("Kraj epizode")
            publish()
        } else next()
    }

    private fun savePosition(force: Boolean) {
        val p = state ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveAt < 5000) return
        lastSaveAt = now
        Store.setPosition(p.current.id, p.positionMs)
        val o = JSONObject()
        o.put("kind", p.kind.name)
        o.put("groupKey", p.groupKey)
        o.put("index", p.index)
        o.put("pos", p.positionMs)
        o.put("rate", p.rate.toDouble())
        o.put("shuffle", p.shuffle)
        o.put("repeat", p.repeat)
        Store.saveLastPlayer(o)
    }

    /* ------------------------------------------------------------ tajmer */
    private fun startTimer(minutes: Int) {
        val s = Store.settings
        val total = minutes * 60_000L
        timer = TimerState(total, total, s.fade * 1000L, s.extend, TPhase.RUN, 0L)
        Bus.night = true
        volume = 1f
        try { mp?.setVolume(1f, 1f) } catch (_: Exception) { }
        if (!isPlaying()) resume()
        Bus.log("Tajmer pokrenut: $minutes min")
        Bus.showToast("Tajmer: $minutes min")
        publish()
    }

    private fun cancelTimer() {
        timer = null
        Bus.night = false
        volume = 1f
        try { mp?.setVolume(1f, 1f) } catch (_: Exception) { }
        Bus.log("Tajmer isključen")
        publish()
    }

    private fun extendTimer() {
        val t = timer
        if (t == null) { startTimer(Store.settings.extend); return }
        val rest = if (t.phase == TPhase.GRACE) 0L else t.leftMs.coerceAtLeast(0)
        timer = t.copy(leftMs = rest + t.extendMin * 60_000L, phase = TPhase.RUN, graceMs = 0L)
        volume = 1f
        Bus.night = true
        try { mp?.setVolume(1f, 1f) } catch (_: Exception) { }
        if (!isPlaying()) resume() else publish()
        vibrate()
        Bus.log("Tajmer produžen za ${t.extendMin} min")
        Bus.showToast("+${t.extendMin} min")
    }

    private fun maybeExtend(source: String) {
        val t = timer ?: return
        if (t.phase != TPhase.FADE && t.phase != TPhase.GRACE) return
        val s = Store.settings
        val enabled = when (source) {
            "shake" -> s.gShake
            "volume" -> s.gVol
            "screen" -> s.gScreen
            else -> true
        }
        if (enabled) {
            Bus.log("Potez ($source) produžava tajmer")
            extendTimer()
        }
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun tickTimer(dt: Long) {
        val t = timer ?: return
        if (t.phase == TPhase.GRACE) {
            val g = t.graceMs - dt
            if (g <= 0) {
                timer = null
                Bus.night = false
                pause()
                Bus.showToast("Tajmer je završio. Reprodukcija je pauzirana.", 4000)
                Bus.log("Tajmer završen, pozicija sačuvana")
            } else timer = t.copy(graceMs = g)
            return
        }
        if (!isPlaying()) return   // odbrojavanje teče samo dok zvuk ide
        val left = t.leftMs - dt
        if (left <= 0) {
            timer = t.copy(leftMs = 0L, phase = TPhase.GRACE, graceMs = 60_000L)
            volume = 0f
            try { mp?.setVolume(0f, 0f) } catch (_: Exception) { }
            pause()
            Bus.log("Tajmer: zvuk utišan, čeka se potez 60 s")
        } else if (left <= t.fadeMs) {
            timer = t.copy(leftMs = left, phase = TPhase.FADE)
            volume = (left.toFloat() / t.fadeMs).coerceIn(0f, 1f)
            try { mp?.setVolume(volume, volume) } catch (_: Exception) { }
        } else {
            timer = t.copy(leftMs = left, phase = TPhase.RUN)
        }
    }

    /* ------------------------------------------------------------ ticker */
    private fun tick() {
        val p = state
        if (p != null && prepared) {
            val pos = try { mp?.currentPosition?.toLong() ?: p.positionMs } catch (_: Exception) { p.positionMs }
            if (!Bus.seeking) state = p.copy(positionMs = pos, playing = isPlaying())
            else state = p.copy(playing = isPlaying())
            if (isPlaying()) savePosition(force = false)
        }
        val speed = Store.settings.timeSpeed.coerceAtLeast(1)
        tickTimer(500L * speed)
        checkVolumeGesture()
        updateSensors()
        Bus.player = state
        Bus.timer = timer
        if (SystemClock.elapsedRealtime() % 5000L < 500L) publish()
    }

    private fun checkVolumeGesture() {
        val lvl = try { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } catch (_: Exception) { return }
        if (lastVolumeLevel >= 0 && lvl != lastVolumeLevel) maybeExtend("volume")
        lastVolumeLevel = lvl
    }

    private fun updateSensors() {
        val need = timer != null && Store.settings.gShake
        if (need == sensorOn) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        if (need) {
            sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
        } else sm.unregisterListener(this)
        sensorOn = need
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val t = timer ?: return
        if (t.phase != TPhase.FADE && t.phase != TPhase.GRACE) return
        val v = event?.values ?: return
        val m = abs(v[0]) + abs(v[1]) + abs(v[2])
        val now = System.currentTimeMillis()
        if (m > 26f && now - lastShakeAt > 1200L) {
            lastShakeAt = now
            maybeExtend("shake")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /* ------------------------------------------------------------ audio focus */
    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (isPlaying()) { pause(); Bus.log("Zvuk prekinut (audio focus $change)") }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (state?.kind == Kind.TRACK) {
                            try { mp?.setVolume(0.25f, 0.25f) } catch (_: Exception) { }
                            Bus.log("Muzika kratko stišana (obaveštenje)")
                        } else if (isPlaying()) { pause(); Bus.log("Pauza zbog drugog zvuka") }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        try { mp?.setVolume(volume, volume) } catch (_: Exception) { }
                    }
                }
            }.build()
        focusRequest = req
        return audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    /* ------------------------------------------------------------ MediaSession + obaveštenje */
    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() { resume() }
        override fun onPause() { pause() }
        override fun onStop() { stopPlayback() }
        override fun onSkipToNext() { next() }
        override fun onSkipToPrevious() { prev() }
        override fun onRewind() { bump(-Store.settings.jump * 1000L) }
        override fun onFastForward() { bump(Store.settings.jump * 1000L) }
        override fun onSeekTo(pos: Long) { seek(pos) }
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == CUSTOM_EXTEND) extendTimer()
        }
    }

    private fun updateSession() {
        val p = state
        val st = if (p?.playing == true) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val ps = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_REWIND or
                    PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_STOP
            )
            .setState(st, p?.positionMs ?: 0L, p?.rate ?: 1f, SystemClock.elapsedRealtime())
            .addCustomAction(CUSTOM_EXTEND, "+${Store.settings.extend} min", R.drawable.ic_moon)
            .build()
        session.setPlaybackState(ps)
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, p?.current?.title ?: "Šaptač")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, p?.groupTitle ?: "Ništa se ne pušta")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, p?.sub ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, p?.durationMs ?: 0L)
        coverBitmap()?.let { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(md.build())
    }

    private fun coverBitmap(): Bitmap? {
        val p = state ?: return null
        val cached = Bus.covers[p.groupKey]
        if (cached != null) return cached
        val embedded = LibraryManager.embeddedCover(this, p.current)
        if (embedded != null) { Bus.covers[p.groupKey] = embedded; return embedded }
        return null
    }

    private fun pi(action: String, extra: Pair<String, Long>? = null): PendingIntent {
        val i = Intent(this, PlayerService::class.java).setAction(action)
        if (extra != null) i.putExtra(extra.first, extra.second)
        return PendingIntent.getService(this, action.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notifAction(icon: Int, label: String, action: String,
                            extra: Pair<String, Long>? = null): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi(action, extra)).build()

    private fun buildNotification(): Notification {
        val p = state
        val playing = p?.playing == true
        val t = timer
        val style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(1, 2, 3)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(p?.current?.title ?: "Šaptač")
            .setContentText(p?.groupTitle ?: "Ništa se ne pušta")
            .setSubText(if (t != null) "Tajmer · ${fmtTime(if (t.phase == TPhase.GRACE) t.graceMs else t.leftMs)}" else "Šaptač")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setStyle(style)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .addAction(notifAction(R.drawable.ic_prev, "Prethodno", ACTION_PREV))
            .addAction(notifAction(R.drawable.ic_b30, "−${Store.settings.jump} s", ACTION_BUMP,
                EXTRA_DELTA to (-Store.settings.jump * 1000L)))
            .addAction(notifAction(if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pauza" else "Pusti", ACTION_TOGGLE))
            .addAction(notifAction(R.drawable.ic_f30, "+${Store.settings.jump} s", ACTION_BUMP,
                EXTRA_DELTA to (Store.settings.jump * 1000L)))
            .addAction(notifAction(R.drawable.ic_next, "Sledeće", ACTION_NEXT))
            .addAction(notifAction(R.drawable.ic_moon, "+${Store.settings.extend} min", ACTION_TIMER_EXTEND))
        coverBitmap()?.let { b.setLargeIcon(it) }
        return b.build()
    }

    private fun publish() {
        Bus.player = state
        Bus.timer = timer
        updateSession()
        notifManager.notify(NOTIF_ID, buildNotification())
    }

    private fun stopPlayback() {
        savePosition(force = true)
        cancelTimer()
        releasePlayer()
        state = null
        Bus.player = null
        Bus.night = false
        abandonFocus()
        wakeLock?.let { if (it.isHeld) it.release() }
        Bus.log("Reprodukcija zaustavljena")
        publish()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        releasePlayer()
        try { unregisterReceiver(receiver) } catch (_: Exception) { }
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sensorOn) try { sm?.unregisterListener(this) } catch (_: Exception) { }
        wakeLock?.let { if (it.isHeld) it.release() }
        abandonFocus()
        session.isActive = false
        session.release()
        super.onDestroy()
    }
}
