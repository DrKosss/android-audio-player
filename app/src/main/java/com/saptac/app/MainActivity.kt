package com.saptac.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/* ------------------------------------------------------------------ *
 * Navigacija (ostaje i posle rotacije ekrana)
 * ------------------------------------------------------------------ */
data class Screen(val v: String, val id: String = "")

object Nav {
    var tab: Section = Section.BOOKS
    var stack: MutableList<Screen> = mutableListOf(Screen("books"))
    var chips: String = "sve"
    var musicTab: String = "folderi"
    var seriesTab: String = "folderi"
    var query: String = ""
    var sheet: String? = null
    var moveName: String = ""
    var moveFrom: Section = Section.NONE
    var moveItems: List<AudioItem> = emptyList()
    var catalog: List<CatalogItem> = emptyList()
    var catalogItem: CatalogItem? = null
    var feedId: String = ""
    var importSelection: MutableSet<String> = linkedSetOf()
    var importSections: MutableMap<String, Section?> = linkedMapOf()
    var scanning: Boolean = false
    var pendingLoose: List<String> = emptyList()
    var pendingTree: String? = null
    var filesTarget: String = "loose"
    var focusSearch: Boolean = false
    var coverTarget: String = ""
    var importSeededVersion: Int = -1
    var scrollToPlaying: Boolean = false
    var normalizeAfterScan: Boolean = false
    var musicSort: String = "folder"
    var standupSort: String = "naziv"
    var plusTab: String = "import"
    var importImportedExpanded: Boolean = true
    var importFreshExpanded: Boolean = true
    var infoItem: AudioItem? = null
    var infoGroup: Group? = null
    var infoFrom: Section = Section.NONE
}

/** Tanki napredak u mini-plejeru. */
class MiniProgress(ctx: Context) : View(ctx) {
    var fraction = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val h = context.dp(2).toFloat()
        val y = height - h
        paint.color = C_LINE
        c.drawRect(0f, y, width.toFloat(), height.toFloat(), paint)
        paint.color = C_AMBER
        c.drawRect(0f, y, width.toFloat() * fraction.coerceIn(0f, 1f), height.toFloat(), paint)
    }
    fun set(value: Float) { fraction = value; invalidate() }
}

class MainActivity : android.app.Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastSig = ""
    private var seekRef: SeekBar? = null
    private var timeLeftRef: TextView? = null
    private var timeRightRef: TextView? = null
    private var wholeRef: TextView? = null
    private var titleRef: TextView? = null
    private var miniProgressRef: MiniProgress? = null
    private var timerToolRef: TextView? = null
    private var timerToastRef: TextView? = null
    private var nightTimeRef: TextView? = null
    private var searchRef: EditText? = null
    private var searchJob: Runnable? = null
    private var seekStart = 0L
    private var currentRoot: View? = null
    private var lastScrollKey: String = ""
    private var savedScrollY: Int = 0
    private var bottomInsetPx = 0
    private var baseRef: LinearLayout? = null
    private var wasStopped = false
    private var skipNextReturn = false
    private var coldStartRestorePending = false

    private val uiTicker = object : Runnable {
        override fun run() {
            if (Bus.toast != null && System.currentTimeMillis() > Bus.toastUntil) Bus.toast = null
            val sig = signature()
            if (sig != lastSig) render() else updateDynamic()
            handler.postDelayed(this, 500L)
        }
    }

    /* ------------------------------------------------------------ životni ciklus */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        // Na Androidu 15+ (edge-to-edge) sistemska navigaciona traka prekriva donji deo
        // sadrzaja. Rezervisemo joj prostor da dugmici za reprodukciju ostanu vidljivi.
        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars()).bottom
            val gestures = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
            val extra = maxOf(bars, gestures, dp(16))
            if (extra != bottomInsetPx) {
                bottomInsetPx = extra
                baseRef?.setPadding(0, 0, 0, bottomInsetPx)
                if (currentRoot != null) render()
            }
            insets
        }
        Store.init(this)
        requestNeededPermissions()
        val coldStart = savedInstanceState == null
        val savedMinimized = savedInstanceState?.getBoolean("playerMinimized", false) ?: false
        savedInstanceState?.getString("navTab")?.let {
            try { Nav.tab = Section.valueOf(it) } catch (_: Exception) { }
        }
        coldStartRestorePending = coldStart
        restoreLastPlayer()
        // Rotacija ne sme da poništi ručno minimiziran plejer; proces ubijen dok
        // je plejer bio otvoren ipak treba da se vrati u poslednji klip.
        if ((coldStart || !savedMinimized) && Bus.player != null) gotoLastPlayer()
        else if (savedMinimized && cur().v == "player") tabHome(Nav.tab)
        if (coldStart) rescan()
        Bus.log("Aplikacija pokrenuta")
        render()
    }

    override fun onResume() {
        super.onResume()
        if (wasStopped) {
            wasStopped = false
            if (!skipNextReturn) {
                restoreLastPlayer()
                gotoLastPlayer()
            }
            skipNextReturn = false
        } else if (skipNextReturn) skipNextReturn = false
        if (Bus.player != null) PlayerService.send(this, PlayerService.ACTION_SYNC)
        handler.removeCallbacks(uiTicker)
        handler.post(uiTicker)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        skipNextReturn = false
        restoreLastPlayer()
        gotoLastPlayer()
        render()
    }

    override fun onStop() {
        wasStopped = true
        super.onStop()
    }

    override fun onPause() {
        handler.removeCallbacks(uiTicker)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("playerMinimized", cur().v != "player")
        outState.putString("navTab", Nav.tab.name)
    }

    /** Spoljašnji izbor fajla/foldera ne sme da se tumači kao povratak u aplikaciju. */
    private fun startExternal(intent: Intent, requestCode: Int? = null) {
        skipNextReturn = true
        if (requestCode != null) startActivityForResult(intent, requestCode)
        else startActivity(intent)
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) rescan()
    }

    private fun rescan() {
        if (Nav.scanning) return
        Nav.scanning = true
        thread {
            val lib = LibraryManager.scan(this)
            runOnUiThread {
                Bus.library = lib
                Bus.libraryVersion++
                Nav.scanning = false
                Bus.log("Pregledano ${lib.items.size} audio fajlova u ${lib.folders.size} foldera")
                if (Nav.normalizeAfterScan) {
                    Nav.normalizeAfterScan = false
                    runNormalizationPipeline()
                }
                if (coldStartRestorePending) {
                    if (Bus.player == null) restoreLastPlayer()
                    if (Bus.player != null) gotoLastPlayer()
                    coldStartRestorePending = false
                }
                render()
            }
        }
    }

    private fun restoreLastPlayer() {
        if (Bus.player != null) return
        val o = Store.lastPlayer()
        // Novi format čuva ceo queue, pa ne zavisi od ponovnog skeniranja biblioteke.
        playerStateFromJson(o)?.let {
            Bus.player = it
            Bus.playRequest = playRequestFromState(it)
            return
        }
        // Stari format: potraži grupu u već očitanoj biblioteci.
        val key = o.optString("groupKey")
        if (key.isBlank()) return
        val kind = try { Kind.valueOf(o.optString("kind")) } catch (_: Exception) { Kind.BOOK }
        val sec = when (kind) {
            Kind.BOOK -> Section.BOOKS
            Kind.EPISODE -> Section.SERIES
            Kind.TRACK -> Section.MUSIC
            Kind.STANDUP -> Section.STANDUP
        }
        val g = LibraryManager.groups(sec).find { it.key == key || it.coverKey == key } ?: return
        val index = o.optInt("index", 0).coerceIn(0, g.items.lastIndex)
        Bus.player = PlayerState(
            kind, key, g.title, g.subtitle, g.items, index, coverIndex(key),
            g.title.take(1).uppercase(Locale.ROOT), g.items[index].durationMs,
            o.optLong("pos", 0), false, o.optDouble("rate", 1.0).toFloat(),
            o.optBoolean("shuffle", false), o.optBoolean("repeat", false)
        )
        Bus.playRequest = Bus.player?.let { playRequestFromState(it) }
    }

    /** Povratak u aplikaciju uvek otvara poslednji klip umesto prethodnog ekrana. */
    private fun gotoLastPlayer() {
        val p = Bus.player ?: return
        Nav.sheet = null
        Bus.night = false
        Nav.stack = mutableListOf(Screen("player"))
        Nav.tab = when (p.kind) {
            Kind.BOOK -> Section.BOOKS
            Kind.EPISODE -> Section.SERIES
            Kind.TRACK -> Section.MUSIC
            Kind.STANDUP -> Section.STANDUP
        }
        Bus.playRequest = playRequestFromState(p)
    }

    /* ------------------------------------------------------------ navigacija */
    private fun cur(): Screen = Nav.stack.lastOrNull() ?: Screen("books")
    private fun go(v: String, id: String = "", replace: Boolean = false) {
        if (replace && Nav.stack.isNotEmpty()) Nav.stack[Nav.stack.lastIndex] = Screen(v, id)
        else Nav.stack.add(Screen(v, id))
        Nav.sheet = null
        Bus.night = false
        render()
    }
    /** + otvara jedan meni za uvoz, sređivanje, internet i izvoz. */
    private fun openPlus(tab: String) {
        Nav.plusTab = tab
        go("plus")
    }
    /** Strelica nadole u plejeru: vrati se na ekran ispod, odnosno na tab sekcije. */
    private fun minimizePlayer() {
        Nav.scrollToPlaying = true
        val under = Nav.stack.size - 2
        if (under >= 0 && Nav.stack[under].v != "player") {
            Nav.stack = Nav.stack.take(under + 1).toMutableList()
            Nav.sheet = null
            Bus.night = false
            render()
            return
        }
        val sec = Bus.player?.let { p ->
            when (p.kind) {
                Kind.BOOK -> Section.BOOKS
                Kind.EPISODE -> Section.SERIES
                Kind.TRACK -> Section.MUSIC
                Kind.STANDUP -> Section.STANDUP
            }
        } ?: Nav.tab
        tabHome(sec)
    }

    /** X u plejeru: zaustavi reprodukciju, skloni mini-plejer i vrati se na tab. */
    private fun closePlayer() {
        val p = Bus.player
        val sec = p?.let { sectionForKind(it.kind) } ?: Nav.tab
        cmd(PlayerService.ACTION_STOP)
        Bus.player = null
        Bus.timer = null
        Bus.playRequest = null
        Bus.night = false
        Bus.seeking = false
        Bus.undo = null
        Nav.sheet = null
        Bus.showToast("Plejer isključen")
        tabHome(sec)
        Nav.scrollToPlaying = false
    }

    private fun onBack() {
        if (cur().v == "player" && Nav.stack.size <= 1) { minimizePlayer(); return }
        if (cur().v == "player") Nav.scrollToPlaying = true
        if (Nav.stack.size > 1) Nav.stack.removeAt(Nav.stack.lastIndex)
        Nav.sheet = null
        Bus.night = false
        render()
    }
    private fun tabHome(sec: Section) {
        if (cur().v == "player") Nav.scrollToPlaying = true
        Nav.tab = sec
        Nav.stack = mutableListOf(Screen(when (sec) {
            Section.BOOKS -> "books"
            Section.SERIES -> "series"
            Section.MUSIC -> "music"
            Section.STANDUP -> "standup"
            else -> "books"
        }))
        Nav.sheet = null; Bus.night = false
        render()
    }
    override fun onBackPressed() {
        when {
            Bus.night -> Bus.night = false
            Nav.sheet != null -> Nav.sheet = null
            cur().v == "player" -> { minimizePlayer(); return }
            Nav.stack.size > 1 -> Nav.stack.removeAt(Nav.stack.lastIndex)
            Nav.tab != Section.BOOKS -> tabHome(Section.BOOKS)
            else -> { super.onBackPressed(); return }
        }
        render()
    }

    /* ------------------------------------------------------------ komande ka servisu */
    private fun cmd(action: String, block: (Intent.() -> Unit)? = null) = PlayerService.send(this, action, block)

    private fun playQueue(kind: Kind, group: Group, index: Int, startMs: Long = 0L,
                         rate: Float = 1f, shuffle: Boolean = false, repeat: Boolean = false,
                         openPlayer: Boolean = true) {
        if (group.items.isEmpty()) return
        Bus.playRequest = PlayRequest(kind, group.key, group.title, group.subtitle,
            group.items, index.coerceIn(0, group.items.lastIndex), startMs, rate, shuffle, repeat)
        cmd(PlayerService.ACTION_PLAY)
        if (openPlayer) go("player") else render()
    }
    private fun playItems(kind: Kind, key: String, title: String, sub: String,
                          items: List<AudioItem>, index: Int, startMs: Long = 0L,
                          shuffle: Boolean = false, repeat: Boolean = false) {
        if (items.isEmpty()) return
        Bus.playRequest = PlayRequest(kind, key, title, sub, items,
            index.coerceIn(0, items.lastIndex), startMs,
            if (kind == Kind.TRACK) 1f else Store.rateForGroup(key), shuffle, repeat)
        cmd(PlayerService.ACTION_PLAY)
        go("player")
    }
    private fun toggle() = cmd(PlayerService.ACTION_TOGGLE)
    private fun bump(sec: Int) = cmd(PlayerService.ACTION_BUMP) { putExtra(PlayerService.EXTRA_DELTA, sec * 1000L) }
    private fun seekTo(ms: Long) = cmd(PlayerService.ACTION_SEEK) { putExtra(PlayerService.EXTRA_POS, ms) }
    private fun next() = cmd(PlayerService.ACTION_NEXT)
    private fun prev() = cmd(PlayerService.ACTION_PREV)
    private fun toast(msg: String, ms: Long = 2200) { Bus.showToast(msg, ms); render() }

    private fun openBook(g: Group) {
        val (index, pos) = LibraryManager.bookStart(g)
        playQueue(Kind.BOOK, g, index, pos, Store.rateForGroup(g.key))
    }
    private fun openStandup(g: Group) {
        val (index, pos) = LibraryManager.bookStart(g)
        playQueue(Kind.STANDUP, g, index, pos, Store.rateForGroup(g.key))
    }
    private fun openEpisode(g: Group, index: Int) {
        val item = g.items[index]
        val start = if (LibraryManager.episodeStatus(item) == "done") 0L else Store.position(item.id)
        playQueue(Kind.EPISODE, g, index, start, Store.rateForGroup(g.key))
    }
    private fun openTrack(g: Group, index: Int) {
        playQueue(Kind.TRACK, g, index, 0L, 1f, Store.settings.shuffle, Store.settings.repeat)
    }

    private fun cycleRate() {
        val p = Bus.player ?: return
        if (p.kind == Kind.TRACK) return
        val opts = floatArrayOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.75f)
        val i = opts.indexOfFirst { kotlin.math.abs(it - p.rate) < 0.01f }.let { if (it < 0) 0 else it }
        val r = opts[(i + 1) % opts.size]
        Store.setRateForGroup(p.groupKey, r)
        cmd(PlayerService.ACTION_RATE) { putExtra(PlayerService.EXTRA_RATE, r) }
        render()
    }

    private fun startTimer(min: Int) {
        cmd(PlayerService.ACTION_TIMER_START) { putExtra(PlayerService.EXTRA_MIN, min) }
        Nav.sheet = null
        Bus.night = true
        render()
    }
    private fun cancelTimer() {
        cmd(PlayerService.ACTION_TIMER_CANCEL)
        Bus.night = false
        render()
    }
    private fun extendTimer() { cmd(PlayerService.ACTION_TIMER_EXTEND); render() }

    /* ------------------------------------------------------------ render */
    private fun signature(): String {
        val s = cur()
        val p = Bus.player
        val t = Bus.timer
        val st = Store.settings
        return listOf(
            s.v, s.id, Nav.tab.name, Nav.chips, Nav.musicTab, Nav.musicSort,
            Nav.seriesTab, Nav.standupSort, Nav.plusTab,
            Nav.importImportedExpanded, Nav.importFreshExpanded,
            Nav.sheet ?: "", Nav.infoItem?.id ?: "", Nav.infoGroup?.key ?: "", Nav.infoFrom.name,
            Nav.feedId, Nav.catalog.size, Nav.catalogItem?.title ?: "",
            Nav.importSelection.size, Nav.scanning, Nav.moveName, Nav.moveFrom.name,
            Bus.night, Bus.libraryVersion, Bus.normalizationVersion, Bus.toast ?: "",
            Bus.onlineScan?.toString() ?: "",
            p?.playing, p?.groupKey, p?.index, p?.current?.id, p?.rate, p?.durationMs,
            t?.phase?.name ?: "off", Bus.undo != null,
            st.jump, st.fade, st.extend, st.gShake, st.gVol, st.gScreen,
            st.nextAuto, st.covers, st.autodl, st.wifiOnly, st.timeSpeed,
            st.shuffle, st.repeat,
            st.normalizeOnline, st.podcastIndexKey.isNotBlank(), st.podcastIndexSecret.isNotBlank(),
            Store.feedCount(), Store.hasAnyFolder()
        ).joinToString("|")
    }

    private fun scrollKey(): String {
        val s = cur()
        return listOf(s.v, s.id, Nav.tab.name, Nav.chips, Nav.musicTab, Nav.musicSort,
            Nav.seriesTab, Nav.standupSort, Nav.plusTab,
            Nav.importImportedExpanded, Nav.importFreshExpanded).joinToString("|")
    }

    private fun findMainScroll(v: View?): ScrollView? {
        if (v == null) return null
        if (v is ScrollView && v.tag == "mainScroll") return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findMainScroll(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun render() {
        val c = this
        seekRef = null; timeLeftRef = null; timeRightRef = null; wholeRef = null
        titleRef = null; miniProgressRef = null
        timerToolRef = null; timerToastRef = null; nightTimeRef = null; searchRef = null

        val keyBefore = scrollKey()
        val oldScroll = findMainScroll(currentRoot)?.scrollY ?: 0

        val navH = c.dp(58) // donja navigacija je uvek tu, bez linije iznad
        // Ulazak u Uvoz: već uvezeni folderi su automatski čekirani.
        // Ako skeniranje završi dok smo na Uvozu, ponovi seed za novu verziju biblioteke.
        if (cur().v == "import" || (cur().v == "plus" && Nav.plusTab == "import")) {
            if (Nav.importSeededVersion != Bus.libraryVersion) {
                val hasLoose = Store.loose().isNotEmpty()
                Bus.library.folders.forEach { f ->
                    // folder je uvezen i ako sekciju nasleđuje od najbližeg roditelja
                    val assigned = Store.folderSectionForPath(f.key)
                    val imported = assigned != null ||
                        (hasLoose && f.key == "Pojedinačni fajlovi")
                    if (imported) {
                        Nav.importSelection.add(f.key)
                        if (!Nav.importSections.containsKey(f.key))
                            Nav.importSections[f.key] = assigned ?: f.assigned ?: f.suggested
                    }
                }
                Nav.importSeededVersion = Bus.libraryVersion
            }
        } else Nav.importSeededVersion = -1

        val root = FrameLayout(c).apply { setBackgroundColor(C_BG) }
        val base = vertical(c)
        base.setPadding(0, 0, 0, bottomInsetPx)
        baseRef = base
        base.addView(statusBar())
        Bus.onlineScan?.let { base.addView(onlineStatusView(it)) }
        val content = screenRoot()
        content.setPadding(0, 0, 0, navH)
        base.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(base, FrameLayout.LayoutParams(-1, -1))

        if (Nav.sheet != null) root.addView(sheetOverlay(), FrameLayout.LayoutParams(-1, -1))
        if (Bus.night) root.addView(nightOverlay(), FrameLayout.LayoutParams(-1, -1))
        Bus.toast?.let {
            if (System.currentTimeMillis() < Bus.toastUntil) {
                val bottom = (if (Bus.player != null && !Bus.night) c.dp(132) else c.dp(74)) +
                    bottomInsetPx + navH
                root.addView(toastView(it), FrameLayout.LayoutParams(-1, -2,
                    Gravity.BOTTOM).apply { leftMargin = c.dp(16); rightMargin = c.dp(16); bottomMargin = bottom })
            } else Bus.toast = null
        }
        // Navigacija je uvek iznad sadržaja, listića i noćnog ekrana — i u plejeru i u podešavanjima.
        root.addView(navBar(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = bottomInsetPx
        })

        val keyAfter = scrollKey()
        savedScrollY = if (cur().v == "series" && !Nav.scrollToPlaying) 0
            else if (lastScrollKey == keyBefore && keyBefore == keyAfter) oldScroll else 0
        lastScrollKey = keyAfter
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        currentRoot = root
        // Poziciju skrola vracamo PRE prvog crtanja, pa nema kratkog skoka na vrh.
        // Posle minimiziranja plejera pomeramo se na označeni fajl koji svira, bez treptaja.
        val restoreY = savedScrollY
        val scrollToPlaying = Nav.scrollToPlaying
        Nav.scrollToPlaying = false
        val sv = findMainScroll(root)
        val marked = if (scrollToPlaying) root.findViewWithTag<View>("nowPlaying") else null
        if (restoreY != 0 || (scrollToPlaying && marked != null && sv != null)) {
            sv?.scrollTo(0, restoreY)
            val observer = sv?.viewTreeObserver
            observer?.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val obs = observer ?: return true
                    if (obs.isAlive) obs.removeOnPreDrawListener(this)
                    val y = if (scrollToPlaying) marked?.let {
                        (offsetInScroll(it, sv!!) - c.dp(12)).coerceAtLeast(0)
                    } ?: restoreY else restoreY
                    sv?.scrollTo(0, y)
                    savedScrollY = y
                    return true
                }
            })
        }
        if (cur().v == "player") Bus.player?.let { maybeFetchCover(it) }
        lastSig = signature()
        updateDynamic()
        if (Nav.focusSearch && cur().v == "series" && Nav.seriesTab == "katalog") {
            searchRef?.requestFocus()
            searchRef?.setSelection(searchRef?.text?.length ?: 0)
            Nav.focusSearch = false
        }
    }

    private fun updateDynamic() {
        val p = Bus.player ?: return
        val dur = p.durationMs.coerceAtLeast(1L)
        val f = p.positionMs.toFloat() / dur
        if (!Bus.seeking) seekRef?.let { it.progress = (p.positionMs / 1000).toInt().coerceIn(0, it.max) }
        timeLeftRef?.text = fmtTime(p.positionMs)
        timeRightRef?.text = "−" + fmtTime(dur - p.positionMs)
        wholeRef?.text = if (p.kind == Kind.BOOK)
            "Cela knjiga: ${(f * 100).roundToInt()}% · preostalo ${fmtShort(dur - p.positionMs)}"
        else "ukupno ${fmtShort(dur)}"
        titleRef?.text = if (p.kind == Kind.BOOK)
            bookFileTitle(p.current) else LibraryManager.displayTitle(p.current)
        miniProgressRef?.set(f)
        Bus.timer?.let { t ->
            val left = if (t.phase == TPhase.GRACE) t.graceMs else t.leftMs
            timerToolRef?.text = fmtTime(left)
            timerToastRef?.text = fmtTime(left)
            nightTimeRef?.text = fmtTime(left)
        }
    }

    /* ------------------------------------------------------------ osnovni delovi */
    private fun statusBar(): View {
        val c = this
        return horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(32))
            setPadding(c.dp(24), c.dp(8), c.dp(24), 0)
            gravity = Gravity.CENTER_VERTICAL
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            addView(text(c, clock, 11.5f, C_T2, bold = true))
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(batteryView())
        }
    }

    private fun batteryView(): View {
        val c = this
        return FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(c.dp(20), c.dp(10))
            background = GradientDrawable().apply {
                cornerRadius = c.dp(3).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(c.dp(1), C_T2)
            }
            val fill = View(c).apply { background = rounded(C_T2, 1f, c) }
            addView(fill, FrameLayout.LayoutParams(c.dp(12), c.dp(6), Gravity.CENTER_VERTICAL).apply {
                leftMargin = c.dp(2)
            })
        }
    }

    private fun header(title: String, plus: Boolean = true, gear: Boolean = true,
                       back: Boolean = false, extras: List<View> = emptyList()): View {
        val c = this
        return horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (back) c.dp(8) else c.dp(18), c.dp(6), c.dp(18), c.dp(10))
            if (back) addView(iconBox(c, R.drawable.ic_back, C_T2, 22, 6) { onBack() })
            addView(text(c, title, if (back) 20f else 26f, C_T1, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            extras.forEach { addView(it) }
            if (plus) addView(iconBox(c, R.drawable.ic_plus, C_T2, 22, 6) { openPlus("import") })
            if (gear) addView(iconBox(c, R.drawable.ic_settings, C_T2, 22, 6) { go("settings") })
        }
    }


    /** Mali prekidač u gornjem toolbaru: Loop / Shuffle. */
    private fun toolbarToggle(res: Int, on: Boolean, description: String,
                              action: (View) -> Unit): FrameLayout =
        iconBox(this, res, if (on) C_AMBER else C_T2, 18, 4) { action(it) }.apply {
            contentDescription = description
            background = ripple(rounded(if (on) C_AMBER_D else Color.TRANSPARENT, 10f, this@MainActivity))
            layoutParams = LinearLayout.LayoutParams(this@MainActivity.dp(26), this@MainActivity.dp(26)).apply {
                marginEnd = this@MainActivity.dp(2)
            }
        }

    private fun toggleShuffle() {
        val s = Store.settings; s.shuffle = !s.shuffle; Store.settings = s
        toast(if (s.shuffle) "Nasumični red uključen" else "Nasumični red isključen")
    }
    private fun toggleRepeat() {
        val s = Store.settings; s.repeat = !s.repeat; Store.settings = s
        toast(if (s.repeat) "Ponavljanje uključeno" else "Ponavljanje isključeno")
    }

    /** Čipovi za sortiranje Muzike koriste stabilne ključeve grupa. */
    private fun musicView(): String = when (Nav.musicSort) {
        "artist" -> "izvodjaci"
        "album" -> "albumi"
        "zanr" -> "zanr"
        else -> "folderi"
    }
    private fun sectionForKind(k: Kind): Section = when (k) {
        Kind.BOOK -> Section.BOOKS
        Kind.EPISODE -> Section.SERIES
        Kind.TRACK -> Section.MUSIC
        Kind.STANDUP -> Section.STANDUP
    }
    private fun infoSectionLabel(sec: Section?): String =
        if (sec == null || sec == Section.NONE) "Nije raspoređeno" else sectionName(sec)

    /** Globalna tanka traka za ručnu internet pretragu cele biblioteke. */
    private fun onlineStatusView(state: OnlineScanState): View {
        val c = this
        val box = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(18), c.dp(2), c.dp(18), c.dp(6))
            background = rounded(C_S1, 0f, c)
        }
        val row = horizontal(c).apply { gravity = Gravity.CENTER_VERTICAL }
        val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
        val label = "Internet: ${state.done} / ${state.total} · ${state.label}"
        row.addView(text(c, label, 11f, C_T2, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (state.running) {
            row.addView(text(c, "Prekini", 11f, C_AMBER, bold = true).apply {
                setPadding(c.dp(8), c.dp(4), c.dp(2), c.dp(4))
                click {
                    Bus.onlineScanCancel = true
                    Bus.onlineScan = state.copy(label = "Prekidam…", message = null)
                    render()
                }
            })
        } else {
            row.addView(text(c, "×", 15f, C_T3, bold = true).apply {
                setPadding(c.dp(8), c.dp(2), c.dp(2), c.dp(2))
                click { Bus.onlineScan = null; render() }
            })
        }
        box.addView(row)
        box.addView(progressLine(c, fraction, 0))
        return box
    }

    /** Ručna internet pretraga cele biblioteke ili samo Stand up poređenje. */
    private fun startFullOnlineScan() {
        val total = Bus.library.items.size
        requestOnlineScan("Internet", total) { ctx, progress ->
            OnlineEnricher.enrichAll(ctx, progress)
        }
    }

    private fun startStandupOnlineCompare() {
        val total = Bus.library.items.count { LibraryManager.sectionOf(it) == Section.STANDUP }
        if (total == 0) { toast("Nema Stand up fajlova za poređenje"); return }
        requestOnlineScan("Stand up", total) { ctx, progress ->
            OnlineEnricher.enrichStandup(ctx, progress)
        }
    }

    /** Wi-Fi podrazumevano; na mobilnom se korisnik pita SVAKI PUT. */
    private fun requestOnlineScan(
        label: String, total: Int,
        worker: (Context, (Int, Int, String) -> Unit) -> Triple<Int, Int, Boolean>
    ) {
        if (Bus.onlineScan?.running == true) { toast("Pretraga već traje"); return }
        if (!Store.settings.normalizeOnline) {
            toast("Uključi internet za sređivanje u + meniju")
            return
        }
        if (Net.onWifi(this)) { launchOnlineScan(label, total, worker); return }
        AlertDialog.Builder(this)
            .setTitle("Mobilni internet")
            .setMessage("Koristi mobilni internet za ovu proveru?")
            .setPositiveButton("Da") { _, _ -> launchOnlineScan(label, total, worker) }
            .setNegativeButton("Ne") { _, _ ->
                Bus.log("$label preskočena: odbijen mobilni internet")
            }
            .show()
    }

    /** Status traka se prikazuje samo dok traje i odmah nestaje po završetku. */
    private fun launchOnlineScan(
        label: String, total: Int,
        worker: (Context, (Int, Int, String) -> Unit) -> Triple<Int, Int, Boolean>
    ) {
        Bus.onlineScanCancel = false
        Bus.onlineScan = OnlineScanState(0, total, "$label · pripremam…", true, null)
        render()
        thread {
            val (made, processed, canceled) = worker(applicationContext) { done, tot, query ->
                Bus.onlineScan = OnlineScanState(done, tot, "$label · $query", true, null)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Bus.onlineScanCancel = false
                Bus.normalizationVersion++
                Bus.libraryVersion++
                Bus.onlineScan = null
                Bus.log("$label: $made predloga" + (if (canceled) " (prekinuto)" else ""))
                toast((if (canceled) "Prekinuto" else "Završeno") + " · $made predloga")
            }
        }
    }

    private fun miniSectionLabel(label: String): View =
        text(this, label, 10.5f, C_AMBER, bold = true).apply {
            setPadding(0, this@MainActivity.dp(12), 0, this@MainActivity.dp(4))
            letterSpacing = 0.14f
        }

    private fun infoKey(): String? = Nav.infoItem?.let { "item:" + it.id }
        ?: Nav.infoGroup?.let { "group:" + it.key }
    private fun currentInfoTitle(): String = Nav.infoItem?.let { LibraryManager.displayTitle(it) }
        ?: Nav.infoGroup?.title ?: ""
    private fun infoOriginalTitle(): String = Nav.infoItem?.title
        ?: Nav.infoGroup?.let { LibraryManager.originalGroupTitle(it) } ?: ""
    private fun infoDisplayName(): String = Nav.infoItem?.let { LibraryManager.displayTitle(it) }
        ?: Nav.infoGroup?.title ?: "fajl"

    private fun closeInfo() {
        Nav.sheet = null
        Nav.infoItem = null
        Nav.infoGroup = null
        Nav.infoFrom = Section.NONE
        render()
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    private fun openInfoDialog(item: AudioItem? = null, group: Group? = null, from: Section) {
        Nav.infoItem = item
        Nav.infoGroup = group
        Nav.infoFrom = from
        Nav.sheet = "info"
        render()
    }

    private fun saveInfoTitle(newTitle: String) {
        val key = infoKey() ?: return
        val draft = newTitle.trim()
        val original = infoOriginalTitle()
        if (draft.isBlank() || draft == original) Store.clearManualTitle(key)
        else Store.setManualTitle(key, draft)
        Bus.libraryVersion++
        Bus.log("Ručno ime: ${draft.ifBlank { original }}")
        Bus.showToast("Ime sačuvano")
        render()
    }

    private fun restoreInfoTitle() {
        val key = infoKey() ?: return
        Store.clearManualTitle(key)
        Bus.libraryVersion++
        Bus.log("Vraćeno originalno ime: ${infoDisplayName()}")
        Bus.showToast("Vraćeno originalno ime")
        render()
    }

    private fun applyInfoSection(sec: Section?) {
        val ids = Nav.infoItem?.let { listOf(it.id) }
            ?: Nav.infoGroup?.items?.map { it.id } ?: emptyList()
        ids.forEach { Store.setOverride(it, sec) }
        Bus.libraryVersion++
        Bus.log("Ručno raspoređeno: ${infoDisplayName()} → ${infoSectionLabel(sec)}")
        Bus.showToast(if (sec == null) "Sekcija uklonjena" else "Premešteno u ${infoSectionLabel(sec)}")
        render()
    }

    /** Dugi klik na fajl ili grupu: podaci, virtuelno ime i ručna sekcija. */
    private fun infoSheet(sheet: LinearLayout) {
        val c = this
        val item = Nav.infoItem
        val group = Nav.infoGroup
        if (item == null && group == null) return
        val key = infoKey() ?: return
        val current = currentInfoTitle()
        val original = infoOriginalTitle()
        val body = vertical(c).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        fun line(label: String, value: String) {
            body.addView(text(c, "$label: $value", 11.5f, C_T2))
        }
        fun pathLine(target: AudioItem?) {
            val value = target?.let { diskPathOf(it) } ?: "—"
            body.addView(text(c, "Puna putanja: $value", 10.5f, C_T2).apply {
                setPadding(0, c.dp(2), 0, c.dp(2))
                setTextIsSelectable(true)
            })
        }

        val head = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(c, if (item != null) "Podaci o fajlu" else "Podaci o grupi",
            17f, C_T1, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconBox(c, R.drawable.ic_x, C_T2, 20, 4) { closeInfo() })
        body.addView(head)
        body.addView(miniSectionLabel("DOSTUPNI PODACI"))

        if (item != null) {
            line("Original", item.title)
            line("Prikaz", current)
            line("Izvođač", item.artist.ifBlank { "—" })
            line("Album", item.album.ifBlank { "—" })
            line("Trajanje", fmtTime(item.durationMs))
            pathLine(item)
            LibraryManager.manualSeasonOf(item)?.let { ms ->
                line("Sezona iz imena", "S" + String.format(Locale.US, "%02d", ms.season))
                ms.episode?.let { line("Epizoda iz imena", "E" + String.format(Locale.US, "%02d", it)) }
            }
            if (LibraryManager.sectionOf(item) == Section.STANDUP || Nav.infoFrom == Section.STANDUP) {
                line("Izvođač iz imena", LibraryManager.standupArtist(item).ifBlank { "—" })
                line("Show iz imena", LibraryManager.standupShow(item).ifBlank { "—" })
            }
        } else if (group != null) {
            val first = group.items.firstOrNull()
            line("Grupa", current)
            line("Original", original)
            line("Fajlova", group.items.size.toString())
            line("Izvođač", first?.artist?.takeIf { it.isNotBlank() } ?: "—")
            line("Album", first?.album?.takeIf { it.isNotBlank() } ?: "—")
            line("Trajanje", fmtTime(group.items.sumOf { it.durationMs }))
            pathLine(first)
            if (Nav.infoFrom == Section.STANDUP && first != null) {
                line("Izvođač iz imena", LibraryManager.standupArtist(first).ifBlank { "—" })
                line("Show iz imena", LibraryManager.standupShow(first).ifBlank { "—" })
            }
        }
        Store.manualTitle(key)?.let { line("Ručno ime", it) }
        val probeId = item?.id ?: group?.items?.firstOrNull()?.id ?: ""
        Store.normalization(probeId)?.let { m ->
            line("Sređeno", "${m.displayTitle} · ${m.source} · ${m.confidence}%")
        }

        val edit = EditText(c).apply {
            setText(current)
            setSelection(text.length)
            hint = "Ručno ime"
            setTextColor(C_T1)
            setHintTextColor(C_T3)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(C_S2, 10f, c)
            setPadding(c.dp(12), c.dp(10), c.dp(12), c.dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = c.dp(14)
                bottomMargin = c.dp(12)
            }
        }
        body.addView(edit)
        val saveRow = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(10) }
        }
        saveRow.addView(actionButton(c, "Sačuvaj ime", true) {
            hideKeyboard(edit)
            saveInfoTitle(edit.text.toString())
        }, LinearLayout.LayoutParams(0, c.dp(42), 1f))
        saveRow.addView(actionButton(c, "Vrati original", false) {
            hideKeyboard(edit)
            restoreInfoTitle()
        }, LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
        body.addView(saveRow)
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard(edit)
                saveInfoTitle(edit.text.toString())
                true
            } else false
        }

        body.addView(miniSectionLabel("RASPOREDI U SEKCIJU"))
        val secRow = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = c.dp(10) }
        }
        listOf(Section.BOOKS, Section.SERIES, Section.MUSIC, Section.STANDUP).forEach { sec ->
            val active = sec == Nav.infoFrom
            val cell = text(c, sectionName(sec), 11.5f, if (active) C_INK else C_T1, bold = active).apply {
                gravity = Gravity.CENTER
                background = rounded(if (active) C_AMBER else C_S2, 10f, c)
                setPadding(c.dp(4), c.dp(10), c.dp(4), c.dp(10))
                click { applyInfoSection(sec) }
            }
            secRow.addView(cell, LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginEnd = c.dp(6) })
        }
        body.addView(secRow)
        body.addView(text(c, "Nije raspoređeno", 12f, C_T3, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(C_S2, 10f, c)
            setPadding(c.dp(8), c.dp(9), c.dp(8), c.dp(9))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(4) }
            click { applyInfoSection(null) }
        })
        body.addView(text(c, "Ime je samo u aplikaciji; fajl na disku ostaje netaknut.",
            11.5f, C_T3).apply { setPadding(0, c.dp(12), 0, 0) })

        val sc = ScrollView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        sc.addView(body, ViewGroup.LayoutParams(-1, -2))
        sheet.addView(sc)
    }

    private fun chipsBar(items: List<Pair<String, String>>, selected: String,
                         bottomDp: Int = 10, onPick: (String) -> Unit): View {
        val c = this
        val inner = horizontal(c).apply { setPadding(c.dp(16), 0, c.dp(16), c.dp(bottomDp)) }
        items.forEach { (k, label) ->
            val p = pill(c, label, k == selected)
            p.click { onPick(k); render() }
            inner.addView(p)
        }
        return HorizontalScrollView(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            isHorizontalScrollBarEnabled = false
            addView(inner)
        }
    }

    private fun tabsBar(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val c = this
        val wrap = vertical(c).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        val row = horizontal(c).apply { setPadding(c.dp(18), 0, c.dp(18), 0) }
        items.forEach { (k, label) ->
            val active = k == selected
            val cell = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = c.dp(22) }
                addView(text(c, label, 13.5f, if (active) C_T1 else C_T2, bold = active).apply {
                    setPadding(0, c.dp(2), 0, c.dp(6))
                })
                addView(View(c).apply { background = rounded(if (active) C_AMBER else Color.TRANSPARENT, 1f, c) },
                    LinearLayout.LayoutParams(-1, c.dp(2)))
                click { onPick(k); render() }
            }
            row.addView(cell)
        }
        wrap.addView(row)
        wrap.addView(View(c).apply { background = rounded(C_LINE, 0f, c) },
            LinearLayout.LayoutParams(-1, c.dp(1)))
        return wrap
    }

    private fun listHost(): LinearLayout = vertical(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setPadding(dp(8), 0, dp(8), dp(8))
    }

    private fun emptyState(message: String, actionLabel: String? = null, action: (() -> Unit)? = null): View {
        val c = this
        return vertical(c).apply {
            setPadding(c.dp(24), c.dp(40), c.dp(24), c.dp(40))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text(c, message, 13.5f, C_T3).apply { gravity = Gravity.CENTER })
            if (actionLabel != null && action != null) {
                val b = actionButton(c, actionLabel, true, R.drawable.ic_plus) { action() }
                b.layoutParams = LinearLayout.LayoutParams(-1, c.dp(42)).apply { topMargin = c.dp(16) }
                addView(b)
            }
        }
    }

    private fun miniPlayer(): View? {
        val c = this
        val p = Bus.player ?: return null
        val outer = FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(58)).apply {
                setMargins(c.dp(8), 0, c.dp(8), c.dp(6))
            }
        }
        val card = horizontal(c).apply {
            background = rounded(C_S2, 16f, c)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(9), 0, c.dp(9), 0)
        }
        card.addView(cover(c, p.cover, p.initial, 38, 9f, coverKeyOf(p.groupKey)))
        val tx = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(10) }
            val firstLine = if (p.kind == Kind.TRACK) LibraryManager.displayTitle(p.current)
                else p.groupTitle
            addView(text(c, firstLine, 14f, C_T1))
            val timerPart = Bus.timer?.let {
                " · tajmer " + fmtTime(if (it.phase == TPhase.GRACE) it.graceMs else it.leftMs)
            } ?: ""
            val secondLine = when (p.kind) {
                Kind.TRACK -> musicArtist(p.current).ifBlank { "Nepoznat izvođač" }
                Kind.BOOK -> bookAuthor(p.current).ifBlank { "Nepoznat autor" }
                else -> LibraryManager.displayTitle(p.current)
            }
            addView(text(c, secondLine + timerPart, 12f, C_T2))
        }
        card.addView(tx)
        card.addView(circleButton(c, 38, C_AMBER,
            if (p.playing) R.drawable.ic_pause else R.drawable.ic_play, C_INK, 16) { toggle() })
        outer.addView(card, FrameLayout.LayoutParams(-1, -1))
        val mp = MiniProgress(c)
        outer.addView(mp, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        miniProgressRef = mp
        outer.click { go("player") }
        return outer
    }

    private fun navBar(): View {
        val c = this
        val wrap = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setBackgroundColor(C_BG)
        }
        val row = horizontal(c).apply { layoutParams = LinearLayout.LayoutParams(-1, c.dp(58)) }
        val items = listOf(
            Triple(Section.MUSIC, "Muzika", R.drawable.ic_music),
            Triple(Section.BOOKS, "Knjige", R.drawable.ic_book),
            Triple(Section.SERIES, "Emisije", R.drawable.ic_radio),
            Triple(Section.STANDUP, "Stand up", R.drawable.ic_standup)
        )
        items.forEach { (sec, label, res) ->
            val active = Nav.tab == sec
            val color = if (active) C_AMBER else C_T3
            val cell = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
                gravity = Gravity.CENTER
                addView(icon(c, res, color, 21))
                addView(text(c, label, 10.5f, color).apply { gravity = Gravity.CENTER })
                click { tabHome(sec) }
            }
            row.addView(cell)
        }
        wrap.addView(row)
        return wrap
    }

    private fun page(vararg parts: View?): LinearLayout = vertical(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        parts.filterNotNull().forEach { addView(it) }
    }

    private fun sectionLabelView(label: String): View = text(this, label, 10.5f, C_AMBER, bold = true).apply {
        setPadding(dp(18), dp(16), dp(18), dp(2))
        letterSpacing = 0.14f
    }

    /** Azurira oznaku sekcije bez ponovnog crtanja celog ekrana (nema skoka skrola). */
    private fun folderBadge(sec: Section?, badge: TextView) {
        badge.text = sectionName(sec)
        badge.setTextColor(if (sec == null) C_T3 else C_AMBER)
        badge.background = if (sec == null) GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Color.rgb(0x3B, 0x35, 0x30))
        } else rounded(C_AMBER_D, 9f, this)
        badge.setPadding(dp(11), dp(6), dp(11), dp(6))
    }

    /** Posle izmene u mestu: sprecava da uiTicker ponovo nacrta ekran. */
    private fun settleUi() { lastSig = signature() }

    /** Visina child-a unutar ScrollView-a (za skok do fajla koji svira). */
    private fun offsetInScroll(v: View, sv: ScrollView): Int {
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== sv) {
            y += cur.top
            cur = cur.parent as? View
        }
        return y
    }

    /** Da li se ovaj fajl trenutno pušta. */
    private fun isPlayingItem(itemId: String): Boolean = Bus.player?.current?.id == itemId
    /** Amber naslov za red koji svira. */
    private fun rowTitle(c: Context, value: String, active: Boolean): TextView =
        text(c, value, 14.5f, if (active) C_AMBER else C_T1, bold = active)
    /** Mali amber trouglić u redu koji svira; prazan slot inače. */
    private fun playingSlot(c: Context, active: Boolean): View = FrameLayout(c).apply {
        layoutParams = LinearLayout.LayoutParams(c.dp(22), c.dp(22))
        if (active) addView(icon(c, R.drawable.ic_play, C_AMBER, 12),
            FrameLayout.LayoutParams(c.dp(12), c.dp(12), Gravity.CENTER))
    }

    /* ------------------------------------------------------------ ekrani */
    private fun screenRoot(): View = when (cur().v) {
        "books" -> booksScreen()
        "series" -> seriesScreen()
        "seriesdetail" -> seriesDetailScreen(cur().id)
        "music" -> musicScreen()
        "standup" -> standupScreen()
        "folder" -> folderScreen(cur().id)
        "player" -> playerScreen()
        "chapters" -> chaptersScreen()
        "settings" -> settingsScreen()
        "arrange" -> arrangeScreen()
        "plus" -> plusScreen()
        "import" -> plusScreen()
        "feeds" -> feedsScreen()
        "feed" -> feedScreen(cur().id)
        "catitem" -> catalogItemScreen()
        "normalize" -> plusScreen()
        else -> booksScreen()
    }

    private fun booksScreen(): View {
        val c = this
        Nav.tab = Section.BOOKS
        val rows = LibraryManager.groups(Section.BOOKS).map { g ->
            val pos = LibraryManager.bookPosition(g)
            val done = Store.isDone(g.key) || pos >= g.durationMs * 0.98 && g.durationMs > 0
            Triple(g, pos, done)
        }
        val filtered = rows.filter { (_, pos, done) ->
            when (Nav.chips) {
                "tok" -> pos > 0 && !done
                "nove" -> pos == 0L
                "zav" -> done
                else -> true
            }
        }
        val list = listHost()
        if (filtered.isEmpty()) list.addView(emptyState(
            "Nema knjiga u ovoj grupi.\nUvoz ide preko + menija."))
        filtered.forEach { (g, pos, done) ->
            val sub = when {
                done -> "${g.subtitle} · završena"
                pos > 0 -> "${g.subtitle} · preostalo ${fmtShort(g.durationMs - pos)}"
                else -> "${g.subtitle} · ${fmtShort(g.durationMs)} · nova"
            }
            val row = rowView(c, { openBook(g) }, {
                openInfoDialog(group = g, from = Section.BOOKS); true
            }, playing = Bus.player?.groupKey == g.key)
            row.addView(cover(c, g.cover, g.initial, 48, 11f, g.coverKey))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(rowTitle(c, g.title, Bus.player?.groupKey == g.key))
                addView(text(c, sub, 12f, C_T2))
                if (pos > 0 && !done && g.durationMs > 0)
                    addView(progressLine(c, pos.toFloat() / g.durationMs))
            }
            row.addView(tx)
            if (done) row.addView(icon(c, R.drawable.ic_check, C_T2, 16))
            if (done) row.alpha = 0.42f
            list.addView(row)
        }
        return page(
            header("Knjige"),
            chipsBar(listOf("sve" to "Sve", "tok" to "U toku", "nove" to "Nove", "zav" to "Završene"),
                Nav.chips) { Nav.chips = it },
            scroll(c, list), miniPlayer()
        )
    }

    /** Virtuelni folderi nepoznatih / mešanih fajlova ne pripadaju Emisijama. */
    private fun unknownSeriesGroup(g: Group): Boolean {
        val t = g.title.lowercase(Locale.ROOT)
        val k = g.key.lowercase(Locale.ROOT)
        val words = listOf("nepoznat", "mešani", "mesani", "neraspoređen", "neuvezen", "unknown", "mixed")
        return words.any { t.contains(it) || k.contains(it) }
    }

    private fun seriesScreen(): View {
        val c = this
        Nav.tab = Section.SERIES
        val tabs = chipsBar(listOf("folderi" to "Folderi", "feedovi" to "Feedovi", "katalog" to "Katalog"),
            Nav.seriesTab, 0) { Nav.seriesTab = it }
        val list = listHost()
        when (Nav.seriesTab) {
            "folderi" -> {
                val groups = LibraryManager.groups(Section.SERIES).filterNot { unknownSeriesGroup(it) }
                if (groups.isEmpty()) list.addView(emptyState(
                    "Nema serija.\nUvoz foldera i fajlova ide preko + menija."))
                groups.forEach { g ->
                    var fresh = 0
                    var started = false
                    g.items.forEach { item ->
                        when (LibraryManager.episodeStatus(item)) {
                            "new" -> fresh++
                            "started" -> started = true
                        }
                    }
                    val row = rowView(c, { go("seriesdetail", g.key) }, {
                        openInfoDialog(group = g, from = Section.SERIES); true
                    }, playing = Bus.player?.groupKey == g.key)
                    row.addView(cover(c, g.cover, g.initial, 48, 11f, g.coverKey))
                    val tx = vertical(c).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                        addView(rowTitle(c, g.title, Bus.player?.groupKey == g.key))
                        addView(text(c, "${g.items.size} epizoda" +
                            (if (fresh > 0) " · $fresh nove" else "") + if (started) " · u toku" else "", 12f, C_T2))
                    }
                    row.addView(tx)
                    row.addView(icon(c, R.drawable.ic_right, C_T3, 16))
                    list.addView(row)
                }
            }
            "feedovi" -> {
                val feeds = Store.feeds()
                list.addView(feedActions(feeds))
                val note = horizontal(c).apply {
                    setPadding(c.dp(18), 0, c.dp(18), c.dp(8))
                    addView(text(c, "${feeds.size} pretplate", 12f, C_T2),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(c, "${feeds.sumOf { it.unread }} nove epizode", 12f, C_T2))
                }
                list.addView(note)
                if (feeds.isEmpty()) list.addView(emptyState("Nema pretplata.\nDodaj RSS adresu emisije."))
                feeds.forEach { f ->
                    val row = rowView(c, { go("feed", f.id) })
                    row.addView(cover(c, coverIndex(f.title), f.title.take(1).uppercase(), 48, 11f))
                    val tx = vertical(c).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                        addView(text(c, f.title, 14.5f, C_T1))
                        addView(text(c, (if (f.unread > 0) "${f.unread} nove · " else "") +
                            "osveženo " + relativeTime(f.lastRefresh), 12f, C_T2))
                    }
                    row.addView(tx)
                    row.addView(icon(c, R.drawable.ic_right, C_T3, 16))
                    list.addView(row)
                }
            }
            else -> {
                val field = searchField()
                list.addView(field)
                val res = filteredCatalog()
                val note = horizontal(c).apply {
                    setPadding(c.dp(18), 0, c.dp(18), c.dp(8))
                    addView(text(c, if (Nav.query.isBlank()) "Predlozi" else "Rezultati iz kataloga", 12f, C_T2),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(c, "${res.size} pronađeno", 12f, C_T2))
                }
                list.addView(note)
                if (res.isEmpty()) list.addView(emptyState("Nema rezultata.\nProveri mrežu ili probaj drugo ime."))
                res.forEach { item ->
                    val row = rowView(c, { Nav.catalogItem = item; go("catitem", item.title) })
                    row.addView(cover(c, coverIndex(item.title), item.title.take(1).uppercase(), 48, 11f))
                    val tx = vertical(c).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                        addView(text(c, item.title, 14.5f, C_T1))
                        addView(text(c, "${item.author} · ${item.genre} · ${item.hint}", 12f, C_T2))
                    }
                    row.addView(tx)
                    row.addView(icon(c, R.drawable.ic_right, C_T3, 16))
                    list.addView(row)
                }
            }
        }
        return page(header("Emisije"), tabs, scroll(c, list), miniPlayer())
    }

    /* ------------------------------------------------------------ Četvrta sekcija: Stand up */
    private fun standupScreen(): View {
        val c = this
        Nav.tab = Section.STANDUP
        val sort = chipsBar(listOf("naziv" to "Naziv", "izvodjac" to "Izvođač"),
            Nav.standupSort) { Nav.standupSort = it }
        val compare = actionButton(c, "Uporedi izvođače / show na internetu", false,
            R.drawable.ic_refresh) { startStandupOnlineCompare() }
        compare.layoutParams = LinearLayout.LayoutParams(-1, c.dp(42)).apply {
            setMargins(c.dp(16), 0, c.dp(16), c.dp(8))
        }
        val rows = LibraryManager.groups(Section.STANDUP).map { g ->
            val pos = LibraryManager.bookPosition(g)
            val done = Store.isDone(g.key) || pos >= g.durationMs * 0.98 && g.durationMs > 0
            Triple(g, pos, done)
        }
        val ordered = when (Nav.standupSort) {
            "izvodjac" -> rows.sortedWith(
                compareBy<Triple<Group, Long, Boolean>> {
                    it.first.items.firstOrNull()?.let { item -> LibraryManager.standupArtist(item) }
                        ?.lowercase(Locale.ROOT) ?: ""
                }.thenBy { it.first.title.lowercase(Locale.ROOT) })
            else -> rows.sortedWith(compareBy { it.first.title.lowercase(Locale.ROOT) })
        }
        val list = listHost()
        ordered.forEach { (g, pos, done) ->
            val sub = when {
                done -> "${g.subtitle} · završena"
                pos > 0 -> "${g.subtitle} · preostalo ${fmtShort(g.durationMs - pos)}"
                else -> "${g.subtitle} · ${fmtShort(g.durationMs)} · nova"
            }
            val row = rowView(c, { openStandup(g) }, {
                openInfoDialog(group = g, from = Section.STANDUP); true
            }, playing = Bus.player?.groupKey == g.key)
            row.addView(cover(c, g.cover, g.initial, 48, 11f, g.coverKey))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(rowTitle(c, g.title, Bus.player?.groupKey == g.key))
                addView(text(c, sub, 12f, C_T2))
                if (pos > 0 && !done && g.durationMs > 0)
                    addView(progressLine(c, pos.toFloat() / g.durationMs))
            }
            row.addView(tx)
            if (done) row.addView(icon(c, R.drawable.ic_check, C_T2, 16))
            if (done) row.alpha = 0.42f
            list.addView(row)
        }
        return page(header("Stand up"), sort, compare, scroll(c, list), miniPlayer())
    }

    private fun feedActions(feeds: List<Feed>): View {
        val c = this
        return horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), 0, c.dp(16), c.dp(10))
            val a = actionButton(c, "Dodaj feed", true, R.drawable.ic_plus) { addFeedDialog() }
            addView(a, LinearLayout.LayoutParams(0, c.dp(42), 1f))
            val b = actionButton(c, "Osveži sve", false, R.drawable.ic_refresh) { refreshFeeds() }
            addView(b, LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
        }
    }

    private fun searchField(): View {
        val c = this
        return FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(40)).apply {
                setMargins(c.dp(16), 0, c.dp(16), c.dp(10))
            }
            background = rounded(C_S2, 12f, c)
            val row = horizontal(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(c.dp(12), 0, c.dp(12), 0) }
            row.addView(icon(c, R.drawable.ic_search, C_T3, 16))
            val et = EditText(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(9) }
                setText(Nav.query)
                hint = "ime emisije ili autora"
                setTextColor(C_T1)
                setHintTextColor(C_T3)
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT
                background = null
                isSingleLine = true
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        Nav.query = s?.toString() ?: ""
                        Nav.focusSearch = true
                        searchJob?.let { handler.removeCallbacks(it) }
                        val job = Runnable { searchCatalog() }
                        searchJob = job
                        handler.postDelayed(job, 600L)
                    }
                })
            }
            row.addView(et)
            searchRef = et
            addView(row, FrameLayout.LayoutParams(-1, -1))
            if (Nav.catalog.isEmpty() && Nav.query.isBlank()) {
                Nav.catalog = Net.offlineCatalog()
            }
        }
    }

    private fun filteredCatalog(): List<CatalogItem> {
        val q = Nav.query.trim().lowercase(Locale.ROOT)
        val base = if (Nav.catalog.isEmpty()) Net.offlineCatalog() else Nav.catalog
        return if (q.isBlank()) base.take(8)
        else base.filter { (it.title + " " + it.author + " " + it.genre).lowercase(Locale.ROOT).contains(q) }
    }

    private fun seriesDetailScreen(id: String): View {
        val c = this
        val g = LibraryManager.groups(Section.SERIES).find { it.key == id }
            ?: return page(header("Emisije", back = true), emptyState("Serija nije pronađena."))
        val started = g.items.indexOfFirst { LibraryManager.episodeStatus(it) == "started" }
        val nextIndex = when {
            started >= 0 -> started
            else -> g.items.indexOfFirst { LibraryManager.episodeStatus(it) != "done" }.let { if (it >= 0) it else 0 }
        }
        val actions = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), 0, c.dp(16), c.dp(10))
            val a = actionButton(c, "Nastavi", true, R.drawable.ic_play) { openEpisode(g, nextIndex) }
            addView(a, LinearLayout.LayoutParams(0, c.dp(42), 1f))
            val b = actionButton(c, "Nasumična", false, R.drawable.ic_shuffle) {
                val pool = g.items.indices.filter { LibraryManager.episodeStatus(g.items[it]) != "done" }
                openEpisode(g, (pool.ifEmpty { g.items.indices.toList() }).random())
            }
            addView(b, LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
        }
        val note = horizontal(c).apply {
            setPadding(c.dp(18), 0, c.dp(18), c.dp(8))
            addView(text(c, "${g.items.count { LibraryManager.episodeStatus(it) != "done" }} nepreslušanih od ${g.items.size}", 12f, C_T2),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(c, g.subtitle, 12f, C_T2))
        }
        val list = listHost()
        g.items.forEachIndexed { i, item ->
            val st = LibraryManager.episodeStatus(item)
            val np = isPlayingItem(item.id)
            val row = rowView(c, { openEpisode(g, i) }, {
                openInfoDialog(item = item, from = Section.SERIES); true
            }, playing = np)
            val status = FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(22), c.dp(22))
                when {
                    np -> addView(icon(c, R.drawable.ic_play, C_AMBER, 14),
                        FrameLayout.LayoutParams(c.dp(14), c.dp(14), Gravity.CENTER))
                    st == "done" -> addView(icon(c, R.drawable.ic_check, C_T2, 16),
                        FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
                    st == "started" -> addView(View(c).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT)
                            setStroke(c.dp(2), C_AMBER)
                        }
                    }, FrameLayout.LayoutParams(c.dp(12), c.dp(12), Gravity.CENTER))
                    else -> addView(View(c).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL; setColor(C_AMBER)
                        }
                    }, FrameLayout.LayoutParams(c.dp(8), c.dp(8), Gravity.CENTER))
                }
            }
            row.addView(status)
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(rowTitle(c, LibraryManager.displayTitle(item), np))
                if (st == "started") addView(progressLine(c,
                    Store.position(item.id).toFloat() / item.durationMs.coerceAtLeast(1)))
            }
            row.addView(tx)
            val pos = Store.position(item.id)
            row.addView(text(c, if (st == "started") "još ${((item.durationMs - pos) / 60000)} min"
            else "${(item.durationMs / 60000)} min", 12f, C_T2))
            if (st == "done" && !np) row.alpha = 0.42f
            list.addView(row)
        }
        return page(header(g.title, plus = false, gear = false, back = true), actions, note,
            scroll(c, list), miniPlayer())
    }

    private fun musicScreen(): View {
        val c = this
        Nav.tab = Section.MUSIC
        val view = musicView()
        Nav.musicTab = view
        val sort = chipsBar(listOf(
            "folder" to "Folderi", "artist" to "Izvođači",
            "album" to "Albumi", "zanr" to "Žanr"
        ), Nav.musicSort) {
            Nav.musicSort = it
            Nav.musicTab = musicView()
        }
        val toggles = listOf(
            toolbarToggle(R.drawable.ic_loop, Store.settings.repeat, "Ponavljanje") { toggleRepeat() },
            toolbarToggle(R.drawable.ic_shuffle, Store.settings.shuffle, "Nasumično") { toggleShuffle() }
        )
        val list = listHost()
        val groups = LibraryManager.groups(Section.MUSIC, view)
        if (groups.isEmpty()) list.addView(emptyState(
            "Nema muzike u ovoj sekciji.\nUvoz ide preko + menija."))
        groups.forEach { g ->
            val row = rowView(c, { go("folder", g.key) }, {
                openInfoDialog(group = g, from = Section.MUSIC); true
            }, playing = Bus.player?.groupKey == g.key)
            row.addView(cover(c, g.cover, g.initial, 48, 11f, g.coverKey))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(rowTitle(c, g.title, Bus.player?.groupKey == g.key))
                addView(text(c, "${g.items.size} numera", 12f, C_T2))
            }
            row.addView(tx)
            row.addView(icon(c, R.drawable.ic_right, C_T3, 16))
            list.addView(row)
        }
        return page(header("Muzika", extras = toggles), sort, scroll(c, list), miniPlayer())
    }

    private fun folderScreen(id: String): View {
        val c = this
        val g = LibraryManager.groups(Section.MUSIC, Nav.musicTab).find { it.key == id }
            ?: LibraryManager.groups(Section.MUSIC).find { it.key == id }
            ?: return page(header("Muzika", back = true), emptyState("Folder nije pronađen."))
        val actions = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), 0, c.dp(16), c.dp(10))
            addView(actionButton(c, "Pusti sve", true, R.drawable.ic_play) { openTrack(g, 0) },
                LinearLayout.LayoutParams(0, c.dp(42), 1f))
            addView(actionButton(c, "Nasumično", false, R.drawable.ic_shuffle) {
                openTrack(g, (g.items.indices).random())
            }, LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
        }
        val list = listHost()
        g.items.forEachIndexed { i, item ->
            val np = isPlayingItem(item.id)
            val row = rowView(c, { openTrack(g, i) }, {
                openInfoDialog(item = item, from = Section.MUSIC); true
            }, playing = np)
            row.addView(playingSlot(c, np))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(rowTitle(c, LibraryManager.displayTitle(item), np))
                addView(text(c, musicArtist(item).ifBlank { "Nepoznat izvođač" }, 12f, C_T2))
            }
            row.addView(tx)
            row.addView(text(c, fmtTime(item.durationMs), 12f, C_T2))
            list.addView(row)
        }
        return page(header(g.title, plus = false, gear = false, back = true), actions,
            scroll(c, list), miniPlayer())
    }

    /* ------------------------------------------------------------ plejer */
    private fun playerScreen(): View {
        val c = this
        val p = Bus.player
        if (p == null) return page(header("Plejer", plus = false, gear = false),
            emptyState("Ništa se ne pušta.\nOtvori knjigu, epizodu ili numeru."))
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setPadding(c.dp(22), 0, c.dp(22), c.dp(6))
        }
        val top = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(34))
            gravity = Gravity.CENTER_VERTICAL
            addView(iconBox(c, R.drawable.ic_down, C_T2, 22, 4) { minimizePlayer() })
            addView(text(c, p.sectionLabel, 10.5f, C_T2, bold = true).apply {
                letterSpacing = 0.16f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconBox(c, R.drawable.ic_x, C_T2, 22, 4) { closePlayer() }.apply {
                contentDescription = "Isključi plejer"
            })
        }
        body.addView(top)
        body.addView(playerArt(p))
        titleRef = text(c,
            if (p.kind == Kind.BOOK) bookFileTitle(p.current)
            else LibraryManager.displayTitle(p.current),
            18f, C_T1, bold = true)
        body.addView(titleRef)
        val playerSub = when (p.kind) {
            Kind.BOOK -> bookAuthor(p.current).ifBlank { "Nepoznat autor" }
            Kind.TRACK -> musicArtist(p.current).ifBlank { "Nepoznat izvođač" }
            else -> p.groupTitle
        } + " · ${p.index + 1} od ${p.queue.size}"
        body.addView(text(c, playerSub, 12.5f, C_T2).apply {
            setPadding(0, c.dp(3), 0, c.dp(12))
        })

        val sb = seek(c, p.durationMs, p.positionMs, object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar?) {
                Bus.seeking = true
                seekStart = p.positionMs
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                Bus.seeking = false
                val target = (sb?.progress ?: 0) * 1000L
                seekTo(target)
                if (p.kind != Kind.TRACK && kotlin.math.abs(target - seekStart) > 2000)
                    Bus.undo = seekStart to System.currentTimeMillis()
                else Bus.undo = null
                render()
            }
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeLeftRef?.text = fmtTime(progress * 1000L)
                    timeRightRef?.text = "−" + fmtTime(p.durationMs - progress * 1000L)
                }
            }
        })
        seekRef = sb
        body.addView(sb)
        val times = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            val l = text(c, fmtTime(p.positionMs), 11.5f, C_T2)
            l.click { toast("Idi na vreme: u pravoj aplikaciji otvara unos HH:MM:SS") }
            timeLeftRef = l
            addView(l, LinearLayout.LayoutParams(0, -2, 1f))
            val r = text(c, "−" + fmtTime(p.durationMs - p.positionMs), 11.5f, C_T2)
            r.gravity = Gravity.END
            timeRightRef = r
            addView(r, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(times)
        wholeRef = text(c, "", 11.5f, Color.rgb(0x7D, 0x75, 0x6A)).apply { gravity = Gravity.CENTER }
        body.addView(wholeRef)

        val tools = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(16) }
            addView(toolButton(c, R.drawable.ic_moon,
                if (Bus.timer != null) fmtTime(Bus.timer!!.leftMs) else "Tajmer",
                Bus.timer != null) { Nav.sheet = "timer"; render() }.also {
                timerToolRef = it.second
            }.first, LinearLayout.LayoutParams(0, c.dp(52), 1f).apply { marginEnd = c.dp(8) })
            addView(toolButton(c, 0, fmtRate(p.rate), false) { cycleRate() }.first,
                LinearLayout.LayoutParams(0, c.dp(52), 1f).apply { marginEnd = c.dp(8) })
            addView(toolButton(c, R.drawable.ic_list,
                if (p.kind == Kind.BOOK) "Poglavlja" else "Lista",
                false) { go("chapters") }.first,
                LinearLayout.LayoutParams(0, c.dp(52), 1f))
        }
        body.addView(tools)

        if (Bus.undo != null && p.kind != Kind.TRACK) {
            val t = horizontal(c).apply {
                layoutParams = LinearLayout.LayoutParams(-1, c.dp(34)).apply { topMargin = c.dp(10) }
                background = rounded(C_S2, 10f, c)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(12), c.dp(6), c.dp(12), c.dp(6))
                addView(text(c, "Skok sa ${fmtTime(Bus.undo!!.first)}", 12f, C_T2),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(c, "PONIŠTI", 11f, C_AMBER, bold = true).apply {
                    click {
                        seekTo(Bus.undo!!.first); Bus.undo = null; render()
                    }
                })
            }
            body.addView(t)
        }
        val t = Bus.timer
        if (t != null && (t.phase == TPhase.FADE || t.phase == TPhase.GRACE)) {
            body.addView(toastRow("#2A1A08", "Stišava se · protresi ili pritisni + produži", "PRODUŽI") { extendTimer() }.first)
        }
        if (t != null) {
            val label = when (t.phase) { TPhase.GRACE -> "čeka potez"; TPhase.FADE -> "stišava se"; else -> "radi" }
            val t2 = toastRow(null, "Tajmer · $label", fmtTime(if (t.phase == TPhase.GRACE) t.graceMs else t.leftMs)) {
                Nav.sheet = "timer"; render()
            }
            timerToastRef = t2.second
            body.addView(t2.first)
        }

        val nextUp = text(c, nextUpText(p), 12f, C_T3).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = c.dp(12) }
        }
        body.addView(nextUp)

        val ctl = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, c.dp(10), 0, c.dp(2))
            gravity = Gravity.CENTER
            addView(circleButton(c, 34, Color.TRANSPARENT, R.drawable.ic_prev,
                Color.rgb(0x8B, 0x83, 0x77), 20) { prev() })
            addView(jumpButton(c, 56, false) { bump(-Store.settings.jump) })
            addView(circleButton(c, 74, C_AMBER,
                if (p.playing) R.drawable.ic_pause else R.drawable.ic_play, C_INK, 30) { toggle() })
            addView(jumpButton(c, 56, true) { bump(Store.settings.jump) })
            addView(circleButton(c, 34, Color.TRANSPARENT, R.drawable.ic_next,
                Color.rgb(0x8B, 0x83, 0x77), 20) { next() })
        }
        body.addView(ctl)
        return page(body)
    }

    private fun playerArt(p: PlayerState): View {
        val c = this
        val size = minOf(resources.displayMetrics.heightPixels * 0.46f, c.dp(204).toFloat()).toInt()
        val frame = FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = c.dp(6); bottomMargin = c.dp(16)
            }
            background = gradientCover(p.cover, 20f, c)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, c.dp(20).toFloat())
                }
            }
            clipToOutline = true
        }
        val ck = coverKeyOf(p.groupKey)
        val bmp = Bus.covers[ck] ?: LibraryManager.customCoverBitmap(c, ck)
            ?: LibraryManager.embeddedCover(c, p.current)?.also {
                Bus.covers[ck] = it
            }
            ?: LibraryManager.cachedOnlineCover(c, ck)
        if (bmp != null) {
            frame.addView(android.widget.ImageView(c).apply {
                setImageBitmap(bmp)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
            frame.addView(View(c).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Color.argb(190, 0, 0, 0)))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val labels = vertical(c).apply {
            gravity = Gravity.BOTTOM
            setPadding(c.dp(16), 0, c.dp(16), c.dp(16))
            addView(text(c, p.groupTitle, 22f, Color.argb(217, 255, 255, 255), serif = true).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            })
            addView(text(c, p.sub.uppercase(Locale.ROOT), 10f, Color.argb(178, 255, 255, 255)).apply {
                letterSpacing = 0.12f
                setPadding(0, c.dp(7), 0, 0)
            })
        }
        frame.addView(labels, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        val edit = FrameLayout(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(150, 0x1B, 0x18, 0x14))
            }
            val iv = icon(c, R.drawable.ic_edit, Color.WHITE, 13)
            addView(iv, FrameLayout.LayoutParams(c.dp(13), c.dp(13), Gravity.CENTER))
            contentDescription = "Promeni omot"
            isClickable = true
            click { pickCover(ck) }
        }
        frame.addView(edit, FrameLayout.LayoutParams(c.dp(28), c.dp(28),
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, c.dp(10), c.dp(10)) })
        // Samo olovka menja omot: dodir bira sliku, dug dodir na olovku skida ručni izbor.
        edit.longClick {
            Store.clearCustomCover(ck)
            Bus.covers.remove(ck)
            coverRequested.remove(ck)
            cmd(PlayerService.ACTION_SYNC)
            render()
            true
        }
        return frame
    }

    private fun toolButton(c: Context, iconRes: Int, value: String, on: Boolean,
                           action: (View) -> Unit): Pair<View, TextView> {
        val box = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(52))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(C_S1); cornerRadius = c.dp(14).toFloat()
                setStroke(c.dp(1), if (on) C_AMBER else C_LINE)
            }
            if (iconRes != 0) addView(icon(c, iconRes, if (on) C_AMBER else C_T1, 18))
            click(action)
        }
        val valueView = text(c, value, 10.5f, if (on) C_AMBER else C_T2).apply {
            gravity = Gravity.CENTER
            if (iconRes == 0) { textSize = 14f; setTextColor(C_T1); typeface = Typeface.DEFAULT_BOLD }
        }
        box.addView(valueView)
        return box to valueView
    }

    private fun toastRow(bgHex: String?, message: String, actionLabel: String,
                         action: (View) -> Unit): Pair<View, TextView> {
        val c = this
        val bg = if (bgHex != null) Color.parseColor(bgHex) else C_S2
        val row = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(34)).apply { topMargin = c.dp(10) }
            background = rounded(bg, 10f, c)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(12), c.dp(6), c.dp(12), c.dp(6))
            addView(text(c, message, 12f, if (bgHex != null) Color.parseColor("#FFD8A3") else C_T2),
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        val b = text(c, actionLabel, 11f, if (bgHex != null) Color.parseColor("#FFD8A3") else C_AMBER, bold = true)
        b.click(action)
        row.addView(b)
        return row to b
    }

    private fun nextUpText(p: PlayerState): String =
        if (p.index + 1 < p.queue.size) "Sledeće: ${LibraryManager.displayTitle(p.queue[p.index + 1])} · ${(p.queue[p.index + 1].durationMs / 60000)} min"
        else "Poslednje u redu"

    /** Posle svakog uvoza: offline parser, pa (po odobrenju) internet obogaćivanje. */
    private fun runNormalizationPipeline() {
        Normalizer.run()
        maybeStartOnlineEnrichment()
    }

    /** Wi-Fi podrazumevano; na mobilnom se korisnik pita SVAKI PUT kada zatreba. */
    private fun maybeStartOnlineEnrichment() {
        if (!Store.settings.normalizeOnline) return
        if (Net.onWifi(this)) { startOnlineEnrichment(); return }
        AlertDialog.Builder(this)
            .setTitle("Mobilni internet")
            .setMessage("Koristi mobilni internet za ovu proveru?")
            .setPositiveButton("Da") { _, _ -> startOnlineEnrichment() }
            .setNegativeButton("Ne") { _, _ ->
                Bus.log("Internet sređivanje preskočeno: odbijen mobilni internet")
            }
            .show()
    }

    private fun startOnlineEnrichment() {
        thread {
            val n = OnlineEnricher.enrich(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Bus.normalizationVersion++
                Bus.libraryVersion++
                Bus.log("Internet sređivanje: $n predloga")
                render()
            }
        }
    }

    /* ------------------------------------------------------------ sređivanje biblioteke (Faza 1) */
    private fun normalizeBody(): LinearLayout {
        val c = this
        val applied = Store.allNormalizations().values
            .sortedWith(compareBy({ it.sortKey ?: it.displayTitle.lowercase(Locale.ROOT) }, { it.rawTitle }))
        val proposals = Store.allProposals().values
            .sortedWith(compareBy({ it.sortKey ?: it.displayTitle.lowercase(Locale.ROOT) }, { it.rawTitle }))
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), c.dp(6), c.dp(16), c.dp(18))
        }

        fun smallBtn(label: String, primary: Boolean, action: (View) -> Unit): TextView =
            text(c, label, 12f, if (primary) C_INK else C_AMBER, bold = true).apply {
                background = rounded(if (primary) C_AMBER else C_S2, 9f, c)
                setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
                click(action)
            }

        body.addView(sectionLabelView("AUTOMATSKI SREĐENO · ${applied.size}"))
        if (applied.isEmpty()) body.addView(emptyState("Još nema sigurnih pogodaka.\nUvoz ide preko + menija, pa se sređivanje pokreće samo."))
        applied.take(40).forEach { m ->
            val box = vertical(c).apply {
                background = rounded(C_S1, 12f, c)
                setPadding(c.dp(12), c.dp(9), c.dp(12), c.dp(9))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(6) }
                addView(text(c, "Pre: ${m.rawTitle}", 11.5f, C_T3))
                addView(text(c, m.displayTitle, 14f, C_T1, bold = true))
                val sec = m.proposedSection?.let { " · ${sectionName(it)}" } ?: ""
                val online = m.onlineSource?.let { " · ${it}" } ?: ""
                val art = if (!m.artworkUrl.isNullOrBlank()) " · omot" else ""
                addView(text(c, "${m.reason} · ${m.confidence}% · ${m.source}$online$art$sec", 11f, C_T2))
            }
            body.addView(box)
        }
        if (applied.size > 40) body.addView(text(c, "… i još ${applied.size - 40}", 11f, C_T3).apply {
            setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(4))
        })

        body.addView(sectionLabelView("ČEKA POTVRDU · ${proposals.size}"))
        if (proposals.isEmpty()) body.addView(emptyState("Nema predloga koji čekaju potvrdu."))
        proposals.forEach { m ->
            val box = vertical(c).apply {
                background = rounded(C_S1, 12f, c)
                setPadding(c.dp(12), c.dp(9), c.dp(12), c.dp(9))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(6) }
                addView(text(c, "Pre: ${m.rawTitle}", 11.5f, C_T3))
                addView(text(c, "Posle: ${m.displayTitle}", 14f, C_T1, bold = true))
                val sec = m.proposedSection?.let { " · predlog: ${sectionName(it)}" } ?: ""
                val online = m.onlineSource?.let { " · ${it}" } ?: ""
                val art = if (!m.artworkUrl.isNullOrBlank()) " · omot" else ""
                addView(text(c, "${m.reason} · ${m.confidence}% · ${m.source}$online$art$sec", 11f, C_T2))
                val btns = horizontal(c).apply {
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(8) }
                    addView(smallBtn("Primeni", true) {
                        Normalizer.applyProposal(m.itemId)
                        Bus.libraryVersion++; render()
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = c.dp(8) })
                    addView(smallBtn("Zadrži original", false) {
                        Normalizer.dismissProposal(m.itemId)
                        Bus.libraryVersion++; render()
                    }, LinearLayout.LayoutParams(-2, -2))
                }
                addView(btns)
            }
            body.addView(box)
        }

        body.addView(sectionLabelView("RADNJE"))
        val check = actionButton(c, "Proveri na internetu", true) { maybeStartOnlineEnrichment() }
        check.layoutParams = LinearLayout.LayoutParams(-1, c.dp(42)).apply { topMargin = c.dp(8) }
        body.addView(check)
        val scanAll = actionButton(c, "Pretraži sve uvezene fajlove", false, R.drawable.ic_refresh) {
            startFullOnlineScan()
        }
        scanAll.layoutParams = LinearLayout.LayoutParams(-1, c.dp(42)).apply { topMargin = c.dp(8) }
        body.addView(scanAll)
        val reset = actionButton(c, "Vrati sve", false) {
            if (Normalizer.restoreAll()) { Bus.libraryVersion++; render() }
        }
        reset.layoutParams = LinearLayout.LayoutParams(-1, c.dp(42)).apply { topMargin = c.dp(8) }
        body.addView(reset)
        body.addView(text(c,
            "Sve je virtuelno: fajlovi se ne preimenuju, ne pomeraju i ne brišu. „Vrati sve“ vraća stanje pre poslednjeg automatskog pokretanja. Internet pretraga pravi samo predloge; ništa se automatski ne primenjuje.",
            11.5f, C_T3).apply { setPadding(c.dp(4), c.dp(12), c.dp(4), c.dp(8)) })
        return body
    }

    private fun normalizeScreen(): View =
        page(header("Sređivanje biblioteke", plus = false, gear = false, back = true),
            scroll(this, normalizeBody()))

    /* ------------------------------------------------------------ poglavlja / lista */
    private fun chaptersScreen(): View {
        val c = this
        val p = Bus.player ?: return page(header("Poglavlja", back = true), emptyState("Ništa se ne pušta."))
        val list = listHost()
        if (p.kind == Kind.BOOK && p.queue.size == 1) {
            val n = 10
            val step = p.durationMs / n
            val here = (p.positionMs / step.coerceAtLeast(1)).toInt()
            for (i in 0 until n) {
                val row = rowView(c, {
                    seekTo(i * step); Bus.undo = p.positionMs to System.currentTimeMillis()
                    Nav.stack.removeAt(Nav.stack.lastIndex); render()
                })
                row.addView(statusDot(c, i == here))
                row.addView(vertical(c).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(text(c, "Deo ${i + 1}", 14.5f, if (i == here) C_AMBER else C_T1))
                    addView(text(c, fmtTime(i * step), 12f, C_T2))
                })
                row.addView(text(c, fmtTime(step), 12f, C_T2))
                list.addView(row)
            }
        } else {
            var offset = 0L
            p.queue.forEachIndexed { i, item ->
                val here = i == p.index
                val row = rowView(c, {
                    playItems(p.kind, p.groupKey, p.groupTitle, p.sub, p.queue, i, 0L)
                    Nav.stack = mutableListOf(Screen("player")); render()
                }, {
                    openInfoDialog(item = item, from = sectionForKind(p.kind)); true
                }, playing = here)
                row.addView(statusDot(c, here))
                row.addView(vertical(c).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(rowTitle(c,
                        if (p.kind == Kind.BOOK) bookFileTitle(item)
                        else LibraryManager.displayTitle(item), here))
                    val chapterSub = if (p.kind == Kind.BOOK)
                        bookAuthor(item).ifBlank { "Nepoznat autor" } + " · "
                    else ""
                    addView(text(c, chapterSub + fmtTime(offset), 12f, C_T2))
                })
                row.addView(text(c, fmtTime(item.durationMs), 12f, C_T2))
                offset += item.durationMs
                list.addView(row)
            }
        }
        val title = if (p.kind == Kind.BOOK) "Poglavlja" else "Lista"
        return page(header(title, plus = false, gear = false, back = true),
            scroll(c, list), miniPlayer())
    }

    private fun statusDot(c: Context, active: Boolean): View = FrameLayout(c).apply {
        layoutParams = LinearLayout.LayoutParams(c.dp(22), c.dp(22))
        if (active) addView(View(c).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C_AMBER) }
        }, FrameLayout.LayoutParams(c.dp(8), c.dp(8), Gravity.CENTER))
    }

    /* ------------------------------------------------------------ podešavanja */
    private val coverRequested = HashSet<String>()

    private fun maybeFetchCover(p: PlayerState) {
        val ck = coverKeyOf(p.groupKey)
        if (Bus.covers.containsKey(ck)) return
        if (Store.customCover(ck) != null) return
        if (!Store.settings.covers) return
        val embedded = LibraryManager.embeddedCover(this, p.current)
        if (embedded != null) { Bus.covers[ck] = embedded; return }
        if (Store.artCacheEntry(ck) != null) return
        if (!coverRequested.add(ck)) return
        val query = "${p.groupTitle} ${p.sub}".trim()
        thread {
            val url = Net.coverUrl(query)
            val bmp = url?.let { Net.bitmap(it) }
            if (bmp != null) runOnUiThread { Bus.covers[ck] = bmp; render() }
        }
    }

    private fun saveSettings(block: (Settings) -> Unit) {
        val s = Store.settings
        block(s)
        Store.settings = s
        cmd(PlayerService.ACTION_SYNC)
        render()
    }

    private fun settingsScreen(): View {
        val c = this
        val s = Store.settings
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(18), 0, c.dp(18), c.dp(16))
        }

        body.addView(sectionLabelView("REPRODUKCIJA"))
        body.addView(settingRow(c, "Skok", "${s.jump} s") {
            saveSettings { it.jump = listOf(15, 30, 45, 60)[(listOf(15, 30, 45, 60).indexOf(it.jump) + 1) % 4] }
        })
        body.addView(settingRow(c, "Sledeća epizoda automatski", null, s.nextAuto) {
            saveSettings { it.nextAuto = !it.nextAuto }
        })

        body.addView(sectionLabelView("TAJMER"))
        body.addView(settingRow(c, "Utišavanje", "${s.fade} s") {
            saveSettings { it.fade = listOf(30, 60, 90)[(listOf(30, 60, 90).indexOf(it.fade) + 1) % 3] }
        })
        body.addView(settingRow(c, "Produžetak", "${s.extend} min") {
            saveSettings { it.extend = listOf(5, 10, 15, 30)[(listOf(5, 10, 15, 30).indexOf(it.extend) + 1) % 4] }
        })
        body.addView(settingRow(c, "Potez: pomeranje telefona", null, s.gShake) {
            saveSettings { it.gShake = !it.gShake }
        })
        body.addView(settingRow(c, "Potez: dugme za jačinu", null, s.gVol) {
            saveSettings { it.gVol = !it.gVol }
        })
        body.addView(settingRow(c, "Potez: paljenje ekrana", null, s.gScreen) {
            saveSettings { it.gScreen = !it.gScreen }
        })
        body.addView(settingRow(c, "Ubrzano vreme (proba)", "×${s.timeSpeed}") {
            saveSettings { it.timeSpeed = listOf(1, 10, 30, 60)[(listOf(1, 10, 30, 60).indexOf(it.timeSpeed) + 1) % 4] }
        })

        body.addView(sectionLabelView("OMOTI"))
        body.addView(settingRow(c, "Preuzimaj omote sa interneta", null, s.covers) {
            saveSettings { it.covers = !it.covers }
        })

        body.addView(sectionLabelView("PODKASTI"))
        body.addView(settingRow(c, "Podkast feedovi",
            "${Store.feedCount()} · ${if (s.wifiOnly) "samo Wi-Fi" else "i mobilni"}") { go("feeds") })
        body.addView(settingRow(c, "Preuzimaj nove epizode", null, s.autodl) {
            saveSettings { it.autodl = !it.autodl }
        })
        body.addView(settingRow(c, "Preuzimanja samo na Wi-Fi", null, s.wifiOnly) {
            saveSettings { it.wifiOnly = !it.wifiOnly }
        })

        body.addView(sectionLabelView("RAD U POZADINI"))
        body.addView(settingRow(c, "Baterija bez ograničenja", ok = true) { })
        body.addView(settingRow(c, "Izuzeta od uspavljivanja", ok = true) { })
        body.addView(settingRow(c, "Dnevnik događaja", "—") { })
        body.addView(text(c,
            "Prototip. Postavke se pamte u pregledaču; u pravoj aplikaciji ove prečice vode u sistemske ekrane.",
            11.5f, C_T3).apply { setPadding(0, c.dp(18), c.dp(4), c.dp(6)) })
        return page(header("Podešavanja", plus = false, gear = false, back = true), scroll(c, body))
    }

    private fun cycleFolder(key: String, silent: Boolean = false) {
        val order = listOf<Section?>(null, Section.MUSIC, Section.BOOKS, Section.SERIES, Section.STANDUP)
        val cur = Store.folderSection(key)
        val i = order.indexOf(cur).let { if (it < 0) 0 else it }
        Store.setFolderSection(key, order[(i + 1) % order.size])
        if (!silent) Bus.libraryVersion++
        Bus.log("Folder ${key.substringAfterLast('/')} → ${sectionName(Store.folderSection(key))}")
    }

    /* ------------------------------------------------------------ raspoređivanje i uvoz */
    private fun arrangeScreen(): View {
        val c = this
        val actions = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), 0, c.dp(16), c.dp(10))
            addView(actionButton(c, "Prihvati predloge", true) {
                LibraryManager.acceptSuggestions(); toast("Raspoređeno: ${Bus.library.folders.count { Store.folderSection(it.key) != null }} foldera")
            }, LinearLayout.LayoutParams(0, c.dp(42), 1f))
            addView(actionButton(c, "Poništi", false) {
                Bus.library.folders.forEach { Store.setFolderSection(it.key, null) }
                Bus.libraryVersion++; render()
            }, LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
        }
        val wait = Bus.library.folders.count { Store.folderSection(it.key) == null }
        val note = horizontal(c).apply {
            setPadding(c.dp(18), 0, c.dp(18), c.dp(8))
            addView(text(c, "${Bus.library.folders.size} foldera, $wait bez predloga", 12f, C_T2),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(c, "${Bus.library.items.size} fajlova", 12f, C_T2))
        }
        val list = listHost()
        if (Bus.library.folders.isEmpty()) list.addView(emptyState("Nema foldera sa audio fajlovima."))
        Bus.library.folders.forEach { f ->
            val sec0 = Store.folderSection(f.key) ?: f.suggested
            val badge = text(c, sectionName(sec0), 11.5f, C_AMBER, bold = true)
            folderBadge(sec0, badge)
            badge.setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
            val row = rowView(c, { })
            row.addView(FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(42), c.dp(42))
                background = rounded(C_S2, 11f, c)
                addView(icon(c, R.drawable.ic_folder, C_T2, 19),
                    FrameLayout.LayoutParams(c.dp(19), c.dp(19), Gravity.CENTER))
            })
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, f.name, 14.5f, C_T1))
                addView(text(c, "${f.count} fajlova", 12f, C_T2))
            })
            val change = {
                cycleFolder(f.key, silent = true)
                folderBadge(Store.folderSection(f.key) ?: f.suggested, badge)
                settleUi()
            }
            row.click { change() }
            badge.click { change() }
            row.addView(badge)
            list.addView(row)
        }
        val footer = text(c, "Dodir na oznaku menja sekciju", 12f, C_T2).apply {
            setPadding(c.dp(18), c.dp(8), c.dp(18), c.dp(10))
        }
        return page(header("Rasporedi fajlove", back = true), actions, note, scroll(c, list), footer)
    }

    private fun hasReadPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Zaglavlje kategorije na Uvoz tabu: klik na strelicu skriva celu kolonu. */
    private fun importSectionHeader(label: String, count: Int, expanded: Boolean,
                                    onToggle: () -> Unit): View {
        val c = this
        val row = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(18), c.dp(10), c.dp(18), c.dp(4))
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(null)
            click { onToggle() }
        }
        val tx = text(c, "$label · $count", 10.5f, C_AMBER, bold = true).apply {
            letterSpacing = 0.14f
        }
        row.addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = icon(c, if (expanded) R.drawable.ic_down else R.drawable.ic_right, C_AMBER, 14)
        arrow.contentDescription = if (expanded) "Sakrij $label" else "Prikaži $label"
        row.addView(arrow)
        return row
    }

    /** + meni: uvoz je samo jedna od opcija, uz sređivanje, internet i izvoz. */
    private fun plusScreen(): View {
        val c = this
        when (cur().v) {
            "import" -> Nav.plusTab = "import"
            "normalize" -> Nav.plusTab = "sort"
        }
        if (!hasReadPermission() && !Environment.isExternalStorageManager()) return importScreen()
        if (Nav.scanning) return importScreen()
        val tabs = chipsBar(listOf(
            "import" to "Uvoz",
            "sort" to "Sređivanje",
            "online" to "Internet",
            "export" to "Izvoz"
        ), Nav.plusTab) { Nav.plusTab = it }
        val body = when (Nav.plusTab) {
            "sort" -> plusSortContent()
            "online" -> plusOnlineContent()
            "export" -> plusExportContent()
            else -> importListContent()
        }
        return page(header("Uvoz i sređivanje", plus = false, gear = false, back = true), tabs, body)
    }

    private fun plusSortContent(): View {
        val c = this
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(18), 0, c.dp(18), c.dp(8))
        }
        val unassigned = Bus.library.folders.count { Store.folderSection(it.key) == null }
        body.addView(sectionLabelView("RASPOREĐIVANJE"))
        body.addView(settingRow(c, "Rasporedi foldere", "$unassigned čekaju") { go("arrange") })
        body.addView(text(c,
            "Neuvezeni folderi i fajlovi se biraju isključivo u + / Uvoz. Nema posebnog foldera nepoznatih fajlova.",
            11.5f, C_T3).apply { setPadding(0, c.dp(8), 0, c.dp(4)) })
        body.addView(normalizeBody())
        return scroll(c, body)
    }

    private fun plusOnlineContent(): View {
        val c = this
        val s = Store.settings
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(18), 0, c.dp(18), c.dp(16))
        }
        body.addView(sectionLabelView("INTERNET SREĐIVANJE"))
        body.addView(settingRow(c, "Koristi internet za sređivanje", null, s.normalizeOnline) {
            saveSettings { it.normalizeOnline = !it.normalizeOnline }
        })
        body.addView(settingRow(c, "Proveri na internetu", "20 upita") { maybeStartOnlineEnrichment() })
        body.addView(settingRow(c, "Pretraži sve uvezene fajlove", "ručno") { startFullOnlineScan() })
        body.addView(settingRow(c, "Mreža", "Wi-Fi · mobilni uz potvrdu") { })
        body.addView(settingRow(c, "Podcast Index ključ",
            if (s.podcastIndexKey.isBlank()) "nije unet" else "••••${s.podcastIndexKey.takeLast(4)}") {
            askPodcastCredential(false)
        })
        body.addView(settingRow(c, "Podcast Index tajna",
            if (s.podcastIndexSecret.isBlank()) "nije uneta" else "••••") {
            askPodcastCredential(true)
        })
        return scroll(c, body)
    }

    private fun plusExportContent(): View {
        val c = this
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(18), 0, c.dp(18), c.dp(16))
        }
        body.addView(sectionLabelView("IZVOZ"))
        val btn = actionButton(c, "Izvezi listu uvezenih fajlova", true) { exportLibrary() }
        btn.layoutParams = LinearLayout.LayoutParams(-1, c.dp(48)).apply { topMargin = c.dp(8) }
        body.addView(btn)
        body.addView(text(c,
            "JSON sadrži sve detalje o fajlovima, grupama, ručnim imenima i virtuelnom sređivanju. Podcast Index ključ i tajna se ne izvoze.",
            11.5f, C_T3).apply { setPadding(0, c.dp(12), 0, 0) })
        return scroll(c, body)
    }

    private fun importPermissionBody(): View {
        val c = this
        return vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(22), c.dp(10), c.dp(22), c.dp(24))
            addView(FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(54), c.dp(54)).apply { bottomMargin = c.dp(16) }
                background = rounded(C_S2, 11f, c)
                addView(icon(c, R.drawable.ic_folder, C_T2, 26),
                    FrameLayout.LayoutParams(c.dp(26), c.dp(26), Gravity.CENTER))
            })
            addView(text(c, "Šaptač traži pristup fajlovima", 19f, C_T1, bold = true))
            addView(text(c,
                "Da bi čitao muziku, knjige i emisije sa telefona i SD kartice, potrebna je dozvola „Pristup svim fajlovima“. Aplikacija ništa ne menja i ništa ne briše; samo čita ono što joj ti pokažeš.",
                13.5f, C_T2).apply { setPadding(0, c.dp(8), 0, c.dp(14)) })
            addView(text(c,
                "Aplikacija nije iz prodavnice, pa ne može da traži dozvolu pojedinačno po folderu. Zato traži ovu jednu.",
                12f, C_T3).apply { setPadding(0, 0, 0, c.dp(22)) })
            addView(actionButton(c, "Dozvoli pristup svim fajlovima", true) { openAllFilesAccess() }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, c.dp(48))
            })
            addView(actionButton(c, "Ne sad", false) { onBack() }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, c.dp(44)).apply { topMargin = c.dp(8) }
            })
            addView(text(c, "U prototipu je ovo simulacija: ništa se stvarno ne čita sa uređaja.",
                11.5f, C_T3).apply { setPadding(0, c.dp(16), 0, 0) })
        }
    }

    private fun importScanningBody(): View =
        emptyState("Pregledam telefon…\nInterna memorija i SD kartica\nTražim audio fajlove i preskačem zvukove obaveštenja.")

    private fun importScreen(): View {
        if (!hasReadPermission() && !Environment.isExternalStorageManager())
            return page(header("Uvoz", plus = false, gear = false, back = true),
                scroll(this, importPermissionBody()))
        if (Nav.scanning)
            return page(header("Uvoz", plus = false, gear = false, back = true), importScanningBody())
        return page(header("Uvoz", plus = false, gear = false, back = true), importListContent())
    }

    /** Sadržaj Uvoz taba: note + skrol lista, bez zaglavlja. */
    private fun importListContent(): View {
        val c = this
        val folders = Bus.library.folders
        val totalFiles = folders.sumOf { it.count }
        val root = vertical(c).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val note = horizontal(c).apply {
            setPadding(c.dp(18), c.dp(4), c.dp(18), c.dp(8))
            addView(text(c, "${folders.size} foldera sa zvukom", 12f, C_T2),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(c, "$totalFiles fajlova", 12f, C_T2))
        }
        root.addView(note)
        val list = listHost()
        var confirmText: TextView? = null
        fun refreshConfirm() {
            confirmText?.text = if (Nav.importSelection.isEmpty()) "Izaberi najmanje jedan folder"
                else "Uvezi ${Nav.importSelection.size} foldera"
            settleUi()
        }
        val hasLoose = Store.loose().isNotEmpty()
        fun isImported(f: FolderInfo): Boolean {
            val assigned = Store.folderSectionForPath(f.key)
            return assigned != null || (hasLoose && f.key == "Pojedinačni fajlovi")
        }
        fun addFolderRow(f: FolderInfo) {
            val included = Nav.importSelection.contains(f.key)
            if (!Nav.importSections.containsKey(f.key)) Nav.importSections[f.key] = f.suggested
            val sec = Nav.importSections[f.key]
            val check = android.widget.CheckBox(c).apply {
                isChecked = included
                layoutParams = LinearLayout.LayoutParams(c.dp(24), c.dp(24))
                buttonTintList = android.content.res.ColorStateList.valueOf(C_AMBER)
                setOnClickListener {
                    if (isChecked) Nav.importSelection.add(f.key) else Nav.importSelection.remove(f.key)
                    refreshConfirm()
                }
            }
            val row = rowView(c, { check.performClick() })
            row.addView(check)
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(10) }
                addView(text(c, f.name, 14.5f, C_T1))
                addView(text(c, "${f.key} · ${f.count} fajlova", 11f, C_T3))
            })
            val badge = text(c, sectionName(sec), 11.5f, C_AMBER, bold = true)
            folderBadge(sec, badge)
            badge.setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
            badge.click {
                val order = listOf<Section?>(null, Section.BOOKS, Section.SERIES, Section.MUSIC, Section.STANDUP)
                val cur = Nav.importSections[f.key]
                val i = order.indexOf(cur).let { if (it < 0) 0 else it }
                Nav.importSections[f.key] = order[(i + 1) % order.size]
                Nav.importSelection.add(f.key)
                check.isChecked = true
                folderBadge(Nav.importSections[f.key], badge)
                refreshConfirm()
            }
            row.addView(badge)
            list.addView(row)
        }
        if (folders.isEmpty()) list.addView(emptyState("Nema novih foldera.\nSve što je pronađeno je već uvezeno."))
        val imported = folders.filter { isImported(it) }
        val fresh = folders.filterNot { isImported(it) }
        list.addView(importSectionHeader("UVEZENI", imported.size, Nav.importImportedExpanded) {
            Nav.importImportedExpanded = !Nav.importImportedExpanded
            render()
        })
        if (Nav.importImportedExpanded) imported.forEach { addFolderRow(it) }
        list.addView(importSectionHeader("NEUVEZENI", fresh.size, Nav.importFreshExpanded) {
            Nav.importFreshExpanded = !Nav.importFreshExpanded
            render()
        })
        if (Nav.importFreshExpanded) fresh.forEach { addFolderRow(it) }
        list.addView(text(c, "Dodir na red uključuje folder, dodir na oznaku menja sekciju",
            12f, C_T2).apply { setPadding(c.dp(18), c.dp(4), c.dp(18), c.dp(10)) })
        list.addView(actionButton(c, "Uvezi pojedinačne fajlove", false) {
            importFiles()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(44)).apply {
                setMargins(c.dp(16), 0, c.dp(16), c.dp(6))
            }
        })
        val confirm = actionButton(c,
            if (Nav.importSelection.isEmpty()) "Izaberi najmanje jedan folder"
            else "Uvezi ${Nav.importSelection.size} foldera", true) { doImport() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(48)).apply {
                setMargins(c.dp(16), 0, c.dp(16), c.dp(18))
            }
        }
        confirmText = confirm.getChildAt(0) as? TextView
        list.addView(confirm)
        list.addView(text(c,
            "Fajlovi ostaju gde jesu. Uvoz samo pamti koji folder pripada kojoj sekciji, pa ništa ne može da se izgubi ili obriše.\nPrototip prikazuje izmišljene foldere; prava aplikacija čita tvoj telefon.",
            11.5f, C_T3).apply { setPadding(c.dp(22), 0, c.dp(22), c.dp(24)) })
        root.addView(scroll(c, list))
        return root
    }

    private fun doImport() {
        if (Nav.importSelection.isEmpty()) { toast("Izaberi najmanje jedan folder"); return }
        Nav.importSelection.forEach { key -> Store.setFolderSection(key, Nav.importSections[key]) }
        Store.importedOnce = true
        runNormalizationPipeline()
        Bus.libraryVersion++
        val count = Nav.importSelection.size
        val wait = Nav.importSelection.count { Nav.importSections[it] == null }
        Nav.importSelection.clear()
        Bus.log("Uvezeno foldera: ${Bus.library.folders.size}")
        toast("Uvezeno: $count foldera" + if (wait > 0) " · $wait čeka raspoređivanje" else "")
        if (wait > 0) { go("arrange"); toast("$wait foldera čeka raspoređivanje", 2600) }
        else onBack()
    }

    /** JSON sa svim podacima o uvezenim fajlovima — format pogodan za analizu na Areni. */
    private fun buildExportJson(): JSONObject {
        fun segArray(key: String) = JSONArray().apply { pathSegments(key).forEach { put(it) } }
        fun secText(sec: Section?) = if (sec == null || sec == Section.NONE) "Nije raspoređeno" else sectionName(sec)
        fun secOrNull(sec: Section?): Any = sec?.let { sectionName(it) } ?: JSONObject.NULL

        val stampUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(Date())

        val views = linkedMapOf(
            Section.BOOKS to listOf("folderi"),
            Section.SERIES to listOf("folderi"),
            Section.MUSIC to listOf("folderi", "izvodjaci", "albumi", "zanr"),
            Section.STANDUP to listOf("folderi")
        )
        val members = HashMap<String, JSONArray>()
        val groupsArr = JSONArray()
        views.forEach { (sec, list) ->
            list.forEach { view ->
                LibraryManager.groups(sec, view).forEach { g ->
                    val ids = JSONArray().apply { g.items.forEach { put(it.id) } }
                    val go = JSONObject().apply {
                        put("section", sectionName(sec)); put("view", view)
                        put("groupKey", g.key); put("groupTitle", g.title)
                        put("groupSubtitle", g.subtitle)
                        put("originalGroupTitle", LibraryManager.originalGroupTitle(g))
                        put("manualGroupTitle", Store.manualTitle("group:" + g.key) ?: JSONObject.NULL)
                        put("coverIndex", g.cover)
                        put("initial", g.initial); put("durationMs", g.durationMs)
                        put("fileCount", g.items.size); put("itemIds", ids)
                        put("lastGroupTime", Store.lastGroupTime(g.key))
                        put("coverKey", g.coverKey)
                        put("isVirtualSeason", g.key.contains("|season|"))
                        put("customCover", Store.customCover(g.coverKey) ?: JSONObject.NULL)
                    }
                    groupsArr.put(go)
                    g.items.forEachIndexed { idx, item ->
                        val m = JSONObject().apply {
                            put("section", sectionName(sec)); put("view", view)
                            put("groupKey", g.key); put("groupTitle", g.title)
                            put("indexInGroup", idx)
                        }
                        members.getOrPut(item.id) { JSONArray() }.put(m)
                    }
                }
            }
        }

        val mixedMap = HashMap<String, Section>()
        LibraryManager.mixed().forEach { (item, looks) -> mixedMap[item.id] = looks }
        val mixedArr = JSONArray()
        mixedMap.forEach { (id, looks) ->
            mixedArr.put(JSONObject().apply { put("itemId", id); put("looksLike", sectionName(looks)) })
        }

        val filesArr = JSONArray()
        Bus.library.items.forEach { item ->
            val fo = audioItemToJson(item)
            fo.put("folderPathSegments", segArray(item.folderKey))
            fo.put("nearestFolderHint", secOrNull(LibraryManager.suggestPath(item.folderKey)))
            val sec = LibraryManager.sectionOf(item)
            fo.put("section", secText(sec))
            fo.put("sectionSource", when {
                Store.override(item.id) != null -> "override"
                LibraryManager.manualSeasonOf(item) != null -> "manualSeason"
                Store.folderSectionForPath(item.folderKey) != null -> "folder"
                LibraryManager.suggestPath(item.folderKey) != null -> "pathSuggestion"
                else -> "none"
            })
            fo.put("positionMs", Store.position(item.id))
            fo.put("done", Store.isDone(item.id))
            fo.put("groups", members[item.id] ?: JSONArray())
            fo.put("mixedSuggestion", mixedMap[item.id]?.let { sectionName(it) } ?: JSONObject.NULL)
            fo.put("normalized", Store.normalization(item.id)?.let { normalizedToJson(it) } ?: JSONObject.NULL)
            fo.put("normalizationProposal", Store.proposal(item.id)?.let { normalizedToJson(it) } ?: JSONObject.NULL)
            fo.put("manualTitle", Store.manualTitle("item:" + item.id) ?: JSONObject.NULL)
            fo.put("displayTitle", LibraryManager.displayTitle(item))
            fo.put("diskPath", diskPathOf(item))
            val ms = LibraryManager.manualSeasonOf(item)
            fo.put("manualSeason", ms?.let { String.format(Locale.US, "S%02d", it.season) } ?: JSONObject.NULL)
            fo.put("manualEpisode", ms?.episode?.let { String.format(Locale.US, "E%02d", it) } ?: JSONObject.NULL)
            fo.put("manualSeriesTitle", ms?.seriesTitle ?: JSONObject.NULL)
            fo.put("standupArtist", LibraryManager.standupArtist(item))
            fo.put("standupShow", LibraryManager.standupShow(item))
            fo.put("musicArtist", musicArtist(item))
            fo.put("bookFileTitle", bookFileTitle(item))
            fo.put("bookGroupTitle", bookGroupTitle(item))
            fo.put("bookAuthor", bookAuthor(item))
            filesArr.put(fo)
        }

        val foldersArr = JSONArray()
        Bus.library.folders.forEach { f ->
            foldersArr.put(JSONObject().apply {
                put("key", f.key); put("name", f.name); put("fileCount", f.count)
                put("pathSegments", segArray(f.key))
                put("suggested", secOrNull(f.suggested))
                put("nearestPathHint", secOrNull(LibraryManager.suggestPath(f.key)))
                put("assigned", secOrNull(Store.folderSection(f.key)))
                put("effective", secText(Store.folderSectionForPath(f.key) ?: f.suggested))
            })
        }

        val raw = JSONObject().apply {
            // API ključ/tajna ostaju samo na telefonu: iz izvoza ih uklanjamo.
            val safeSettings = JSONObject(Store.rawObject("settings").toString())
            safeSettings.remove("podcastIndexKey")
            safeSettings.remove("podcastIndexSecret")
            put("settings", safeSettings)
            put("positions", Store.rawObject("positions"))
            put("done", Store.rawObject("done"))
            put("overrides", Store.rawObject("overrides"))
            put("folderSections", Store.rawObject("folders"))
            put("rates", Store.rawObject("rates"))
            put("recentGroups", Store.rawObject("recent"))
            put("customCovers", Store.rawObject("covers"))
            put("looseFiles", Store.rawObject("loose"))
            put("lastPlayer", Store.lastPlayer())
            put("feeds", Store.rawArray("feeds"))
            put("treeUris", Store.rawArray("trees"))
            put("logs", Store.rawArray("log"))
            put("normalizations", Store.rawObject("normalizations"))
            put("normalizationProposals", Store.rawObject("normalizationProposals"))
            put("normalizationDismissed", Store.allDismissed())
            put("normalizationSnapshot", Store.normalizationSnapshot())
            put("manualTitles", Store.rawObject("manualTitles"))
            put("normalizationArtCache", Store.artCache())
            put("normalizationOnlineCursor", Store.onlineCursor)
            put("importedOnce", Store.importedOnce)
        }

        return JSONObject().apply {
            put("format", "saptac-library-export-v1")
            put("exportedAtUtc", stampUtc)
            put("app", JSONObject().apply {
                put("package", packageName)
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
            })
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER); put("model", Build.MODEL)
                put("androidRelease", Build.VERSION.RELEASE); put("sdk", Build.VERSION.SDK_INT)
                put("locale", Locale.getDefault().toString())
                put("timeZone", java.util.TimeZone.getDefault().id)
            })
            put("counts", JSONObject().apply {
                put("files", Bus.library.items.size)
                put("folders", Bus.library.folders.size)
                put("groups", groupsArr.length())
                put("mixed", mixedArr.length())
                put("feeds", Store.rawArray("feeds").length())
                put("treeUris", Store.rawArray("trees").length())
                put("normalizedApplied", Store.allNormalizations().size)
                put("normalizationProposals", Store.allProposals().size)
                put("manualTitles", Store.allManualTitles().size)
            })
            put("files", filesArr)
            put("folders", foldersArr)
            put("groups", groupsArr)
            put("mixed", mixedArr)
            put("rawStores", raw)
        }
    }

    private fun exportLibrary() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "saptac-fajlovi-$stamp.json")
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try { startExternal(i, 2004) } catch (_: Exception) { }
    }

    private fun importTree() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startExternal(i, 2001)
    }

    private fun pickCover(groupKey: String) {
        Nav.coverTarget = groupKey
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startExternal(i, 2003) }
        catch (_: Exception) { Nav.coverTarget = "" }
    }

    private fun importFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startExternal(i, 2002)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return
        when (requestCode) {
            2001 -> {
                val uri = data.data ?: return
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Nav.pendingTree = uri.toString()
                Nav.filesTarget = "tree"
                Nav.sheet = "files"
                render()
            }
            2002 -> {
                val uris = ArrayList<String>()
                data.data?.let { uris.add(it.toString()) }
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it.toString()) }
                }
                uris.distinct().forEach {
                    try { contentResolver.takePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    catch (_: Exception) { }
                }
                Nav.pendingLoose = uris.distinct()
                Nav.filesTarget = "loose"
                Nav.sheet = "files"
                render()
            }
            2003 -> {
                val uri = data.data ?: return
                val key = Nav.coverTarget
                Nav.coverTarget = ""
                if (key.isBlank()) return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                Store.setCustomCover(key, uri.toString())
                Bus.covers.remove(key)
                coverRequested.remove(key)
                LibraryManager.customCoverBitmap(this, key)
                cmd(PlayerService.ACTION_SYNC)
                render()
            }
            2004 -> {
                val uri = data.data ?: return
                val json = buildExportJson()
                val count = Bus.library.items.size
                thread {
                    try {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toString(2).toByteArray(Charsets.UTF_8))
                        }
                        Bus.log("Izvezena lista fajlova: $count fajlova")
                    } catch (e: Exception) {
                        Bus.log("Izvoz nije uspeo: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun applyFilesSection(sec: Section) {
        if (Nav.filesTarget == "tree") {
            Nav.pendingTree?.let { Store.addTreeUri(it) }
            Nav.pendingTree = null
        } else {
            Nav.pendingLoose.forEach { Store.setLoose(it, sec) }
            Nav.pendingLoose = emptyList()
        }
        Nav.sheet = null
        Nav.normalizeAfterScan = true
        rescan()
    }

    /** Ključ/tajna se unose SAMO u aplikaciji; nikad se ne traže u chatu ni upisuju u repozitorijum. */
    private fun askPodcastCredential(secret: Boolean) {
        val c = this
        val current = if (secret) Store.settings.podcastIndexSecret else Store.settings.podcastIndexKey
        val input = EditText(c).apply {
            inputType = if (secret)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            setText(current)
            setSelection(text.length)
        }
        AlertDialog.Builder(c)
            .setTitle(if (secret) "Podcast Index tajna" else "Podcast Index ključ")
            .setMessage("Unesi svoj besplatni API ključ/tajnu. Ostaje samo na telefonu; ne šalje se u chat ni u izvoz.")
            .setView(input)
            .setPositiveButton("Sačuvaj") { _, _ ->
                val v = input.text.toString().trim()
                saveSettings { if (secret) it.podcastIndexSecret = v else it.podcastIndexKey = v }
            }
            .setNegativeButton("Otkaži", null)
            .show()
    }

    private fun openAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) startExternal(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
            else openAppSettings()
        } catch (_: Exception) { openAppSettings() }
    }
    private fun openAppSettings() {
        try { startExternal(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }
    /* ------------------------------------------------------------ feedovi */
    private fun feedsScreen(): View {
        val c = this
        val feeds = Store.feeds()
        val list = listHost()
        feeds.forEach { f ->
            val row = rowView(c, { go("feed", f.id) })
            row.addView(cover(c, coverIndex(f.title), f.title.take(1).uppercase(), 48, 11f))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, f.title, 14.5f, C_T1))
                addView(text(c, (if (f.unread > 0) "${f.unread} nove · " else "") +
                    "${f.episodes.count { it.local.isNotBlank() }} u telefonu · ${f.author}", 12f, C_T2))
            }
            row.addView(tx)
            row.addView(icon(c, R.drawable.ic_right, C_T3, 16))
            list.addView(row)
        }
        if (feeds.isEmpty()) list.addView(emptyState("Nema pretplata.\nDodaj adresu RSS feeda."))
        return page(header("Podkast feedovi", back = true), feedActions(feeds), scroll(c, list))
    }

    private fun feedScreen(id: String): View {
        val c = this
        val f = Store.feeds().find { it.id == id }
            ?: return page(header("Emisije", back = true), emptyState("Feed nije pronađen."))
        val actions = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), 0, c.dp(16), c.dp(10))
            addView(actionButton(c, "Pusti poslednju", true, R.drawable.ic_play) {
                playFeedEpisode(f, 0)
            }, LinearLayout.LayoutParams(0, c.dp(42), 1f))
            addView(actionButton(c, "Osveži", false, R.drawable.ic_refresh) { refreshOneFeed(f.id) },
                LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
        }
        val note = horizontal(c).apply {
            setPadding(c.dp(18), 0, c.dp(18), c.dp(8))
            addView(text(c, "${f.episodes.size} epizoda · ${f.unread} novih", 12f, C_T2),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(c, "osveženo " + relativeTime(f.lastRefresh), 12f, C_T2))
        }
        val list = listHost()
        if (f.episodes.isEmpty()) list.addView(emptyState("Nema epizoda. Osveži feed."))
        f.episodes.forEachIndexed { i, ep ->
            val local = ep.local.isNotBlank() && File(ep.local).exists()
            val np = local && Bus.player?.current?.uri == Uri.fromFile(File(ep.local)).toString()
            val row = rowView(c, { playFeedEpisode(f, i) }, playing = np)
            row.addView(FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(42), c.dp(42))
                background = rounded(C_S2, 11f, c)
                addView(icon(c, if (local) R.drawable.ic_check else R.drawable.ic_down, C_T2, 19),
                    FrameLayout.LayoutParams(c.dp(19), c.dp(19), Gravity.CENTER))
            })
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(rowTitle(c, ep.title, np))
                addView(text(c, if (local) "u telefonu" else "nije preuzeto · dodir preuzima i pušta", 12f, C_T2))
            })
            row.addView(text(c, if (ep.durationMs > 0) "${ep.durationMs / 60000} min" else "—", 12f, C_T2))
            list.addView(row)
        }
        return page(header(f.title, back = true), actions, note, scroll(c, list), miniPlayer())
    }

    private fun playFeedEpisode(f: Feed, index: Int) {
        val ep = f.episodes.getOrNull(index) ?: return
        if (ep.local.isNotBlank() && File(ep.local).exists()) { playLocalEpisode(f, ep); return }
        if (Store.settings.wifiOnly && !Net.onWifi(this)) {
            toast("Preuzimanja samo na Wi-Fi"); return
        }
        toast("Osvežavanje traje samo na telefona; u prototipu ništa ne dolazi sa mreže")
        thread {
            val safeId = f.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(filesDir, "podcasts/${safeId}_${index}.mp3")
            val ok = Net.download(ep.url, file) { }
            runOnUiThread {
                if (!ok) { toast("Osvežavanje traje samo na telefona; u prototipu ništa ne dolazi sa mreže"); return@runOnUiThread }
                val feeds = Store.feeds().toMutableList()
                val fi = feeds.indexOfFirst { it.id == f.id }
                if (fi >= 0) {
                    val eps = feeds[fi].episodes.toMutableList()
                    eps[index] = ep.copy(local = file.absolutePath)
                    feeds[fi] = feeds[fi].copy(episodes = eps, unread = (feeds[fi].unread - 1).coerceAtLeast(0))
                    Store.saveFeeds(feeds)
                }
                Bus.log("Epizoda preuzeta: ${ep.title}")
                playLocalEpisode(f, ep.copy(local = file.absolutePath))
            }
        }
    }

    private fun playLocalEpisode(f: Feed, ep: FeedEpisode) {
        val file = File(ep.local)
        val item = AudioItem(
            id = "feed:${f.id}:${ep.title}", uri = Uri.fromFile(file).toString(),
            title = ep.title, artist = f.author, album = f.title,
            durationMs = if (ep.durationMs > 0) ep.durationMs else file.length() * 8_000L / 128_000L,
            folderKey = "feed:${f.id}", folderName = f.title,
            size = file.length(), added = file.lastModified(), track = 0,
            path = file.absolutePath
        )
        playItems(Kind.EPISODE, "feed:${f.id}", f.title, ep.title, listOf(item), 0)
    }

    private fun addFeedDialog() {
        val c = this
        val input = EditText(c).apply {
            hint = "https://example.com/feed.xml"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(C_T1)
            setHintTextColor(C_T3)
        }
        val pad = FrameLayout(c).apply {
            setPadding(c.dp(20), c.dp(12), c.dp(20), 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(c).setTitle("Adresa RSS feeda").setView(pad)
            .setPositiveButton("Dodaj") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http")) return@setPositiveButton
                
                thread {
                    try {
                        val (title, eps) = Net.fetchFeed(url)
                        runOnUiThread {
                            val feeds = Store.feeds().toMutableList()
                            feeds.add(Feed(url.hashCode().toString(), title, url,
                                url.substringAfter("//").substringBefore('/'),
                                eps.size, System.currentTimeMillis(), eps))
                            Store.saveFeeds(feeds)
                            Bus.log("Feed dodat: $title (${eps.size} epizoda)")
                            toast("Feed dodat. U pravoj aplikaciji ovde se čita RSS i slika.")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Osvežavanje traje samo na telefona; u prototipu ništa ne dolazi sa mreže") }
                    }
                }
            }.setNegativeButton("Otkaži", null).show()
    }

    private fun refreshOneFeed(id: String) = refreshFeeds(listOf(id))

    private fun refreshFeeds(only: List<String>? = null) {
        toast("Osvežavanje traje samo na telefona; u prototipu ništa ne dolazi sa mreže")
        thread {
            val feeds = Store.feeds().toMutableList()
            feeds.forEachIndexed { i, f ->
                if (only != null && f.id !in only) return@forEachIndexed
                try {
                    val (title, eps) = Net.fetchFeed(f.url)
                    val oldTitles = f.episodes.map { it.title }.toSet()
                    val merged = eps.map { e ->
                        val old = f.episodes.find { it.title == e.title }
                        if (old != null) e.copy(local = old.local) else e
                    }
                    feeds[i] = f.copy(title = title.ifBlank { f.title }, episodes = merged,
                        unread = merged.count { it.title !in oldTitles },
                        lastRefresh = System.currentTimeMillis())
                } catch (_: Exception) { }
            }
            Store.saveFeeds(feeds)
            runOnUiThread { Bus.log("Feedovi osveženi"); render() }
        }
    }

    private fun searchCatalog() {
        val q = Nav.query.trim()
        if (q.isBlank()) { Nav.catalog = Net.offlineCatalog(); render(); return }
        thread {
            val result = try {
                val r = Net.searchCatalog(q)
                if (r.isEmpty()) Net.offlineCatalog().filter {
                    (it.title + it.author + it.genre).contains(q, true)
                } else r
            } catch (_: Exception) {
                Net.offlineCatalog().filter { (it.title + it.author + it.genre).contains(q, true) }
            }
            runOnUiThread { Nav.catalog = result; render() }
        }
    }

    private fun followFeed(item: CatalogItem) {
        if (item.feedUrl.isBlank()) {
            toast("Pregled epizoda stiže sa feeda; u prototipu nije dostupan")
            return
        }
        
        thread {
            try {
                val (title, eps) = Net.fetchFeed(item.feedUrl)
                runOnUiThread {
                    val feeds = Store.feeds().toMutableList()
                    if (feeds.none { it.url == item.feedUrl }) {
                        feeds.add(Feed(item.feedUrl.hashCode().toString(),
                            title.ifBlank { item.title }, item.feedUrl, item.author,
                            eps.size, System.currentTimeMillis(), eps))
                        Store.saveFeeds(feeds)
                    }
                    Bus.log("Zapraćeno: ${item.title}")
                    toast("Zapraćeno: ${item.title}")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Osvežavanje traje samo na telefona; u prototipu ništa ne dolazi sa mreže") }
            }
        }
    }

    private fun relativeTime(ms: Long): String {
        if (ms <= 0) return "nikad"
        val min = (System.currentTimeMillis() - ms) / 60000
        return when {
            min < 1 -> "upravo sad"
            min < 60 -> "pre $min min"
            min < 1440 -> "pre ${min / 60} h"
            else -> "pre ${min / 1440} dana"
        }
    }

    /* ------------------------------------------------------------ katalog */
    private fun catalogItemScreen(): View {
        val c = this
        val item = Nav.catalogItem ?: return page(header("Katalog", back = true), emptyState("Nema podataka."))
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(20), c.dp(6), c.dp(20), c.dp(16))
        }
        body.addView(cover(c, coverIndex(item.title), item.title.take(1).uppercase(), 132, 20f))
        body.addView(text(c, "${item.author} · ${item.genre} · ${item.hint}", 12.5f, C_T2).apply {
            setPadding(0, c.dp(14), 0, c.dp(16))
        })
        val followed = Store.feeds().any { it.url == item.feedUrl && item.feedUrl.isNotBlank() }
        val actions = horizontal(c).apply {
            addView(actionButton(c, if (followed) "Zapraćeno" else "Zaprati", true) { followFeed(item) },
                LinearLayout.LayoutParams(0, c.dp(42), 1f))
            addView(actionButton(c, "Pregled epizoda", false) {
                if (item.feedUrl.isBlank()) toast("Pregled epizoda stiže sa feeda; u prototipu nije dostupan")
                else {
                    val id = item.feedUrl.hashCode().toString()
                    if (Store.feeds().none { it.id == id }) followFeed(item)
                    else go("feed", id)
                }
            }, LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
        }
        body.addView(actions)
        body.addView(sectionLabelView("Slično ovome"))
        val similar = (if (Nav.catalog.isEmpty()) Net.offlineCatalog() else Nav.catalog)
            .filter { it.genre == item.genre && it.title != item.title }.take(3)
        similar.forEach { s ->
            val row = rowView(c, { Nav.catalogItem = s; go("catitem", s.title) })
            row.addView(cover(c, coverIndex(s.title), s.title.take(1).uppercase(), 46, 10f))
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, s.title, 14.5f, C_T1))
                addView(text(c, "${s.author} · ${s.hint}", 12f, C_T2))
            })
            body.addView(row)
        }
        return page(header(item.title, back = true), scroll(c, body))
    }

    /* ------------------------------------------------------------ listići (sheet) */
    private fun sheetOverlay(): View {
        val c = this
        val isInfo = Nav.sheet == "info"
        val navH = c.dp(58)
        val scrim = FrameLayout(c).apply {
            background = android.graphics.drawable.ColorDrawable(Color.argb(184, 0, 0, 0))
            click { Nav.sheet = null; render() }
        }
        val radius = c.dp(26).toFloat()
        val sheet = vertical(c).apply {
            background = GradientDrawable().apply {
                setColor(C_S1)
                cornerRadii = if (isInfo)
                    floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
                else
                    floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
                setStroke(c.dp(1), C_LINE)
            }
            setPadding(c.dp(18), c.dp(10), c.dp(18),
                if (isInfo) c.dp(12) else c.dp(8) + navH + bottomInsetPx)
            click { }
            if (!isInfo) {
                addView(View(c).apply { background = rounded(Color.rgb(0x3A, 0x36, 0x32), 2f, c) },
                    LinearLayout.LayoutParams(c.dp(36), c.dp(4)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = c.dp(16)
                    })
            }
        }
        when (Nav.sheet) {
            "timer" -> timerSheet(sheet)
            "files" -> filesSheet(sheet)
            "move" -> moveSheet(sheet)
            "info" -> infoSheet(sheet)
        }
        val lp = if (isInfo)
            FrameLayout.LayoutParams(-1, -1, Gravity.TOP).apply {
                setMargins(0, c.dp(32), 0, navH + bottomInsetPx)
            }
        else FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        scrim.addView(sheet, lp)
        return scrim
    }

    private fun timerSheet(sheet: LinearLayout) {
        val c = this
        val t = Bus.timer
        sheet.addView(text(c, "Tajmer za spavanje", 17f, C_T1, bold = true).apply {
            setPadding(0, 0, 0, c.dp(14))
        })
        val presets = horizontal(c).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        listOf(15, 30, 45, 60).forEach { m ->
            val active = t != null && (t.totalMs / 60000).toInt() == m
            val cell = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, c.dp(66), 1f).apply { marginEnd = c.dp(8) }
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(C_S2); cornerRadius = c.dp(16).toFloat()
                    if (active) setStroke(c.dp(2), C_AMBER)
                }
                addView(text(c, m.toString(), 22f, if (active) C_AMBER else C_T1, bold = true))
                addView(text(c, "min", 10f, if (active) C_AMBER else C_T2))
                click { startTimer(m) }
            }
            presets.addView(cell)
        }
        sheet.addView(presets)
        if (t != null) {
            val row = horizontal(c).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(16) }
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(C_LINE, 0f, c)
                setPadding(0, c.dp(14), 0, 0)
                val left = vertical(c).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(text(c, "Preostalo", 12f, C_T2))
                    addView(text(c, fmtTime(if (t.phase == TPhase.GRACE) t.graceMs else t.leftMs),
                        22f, C_AMBER, bold = true))
                }
                addView(left)
                addView(actionButton(c, "Isključi", false) { cancelTimer() }.apply {
                    layoutParams = LinearLayout.LayoutParams(c.dp(110), c.dp(42))
                })
            }
            sheet.addView(row)
        }
        sheet.addView(text(c, "Utišavanje i produžetak", 12.5f, C_T2).apply {
            setPadding(0, c.dp(16), 0, c.dp(8))
        })
        val picks = horizontal(c)
        val s = Store.settings
        val f = text(c, "utišavanje ${s.fade} s", 11.5f, C_T2, bold = true).apply {
            background = rounded(C_S2, 9f, c)
            setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
            click {
                saveSettings { it.fade = listOf(30, 60, 90)[(listOf(30, 60, 90).indexOf(it.fade) + 1) % 3] }
            }
        }
        picks.addView(f, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = c.dp(8) })
        val e = text(c, "produžetak ${s.extend} min", 11.5f, C_T2, bold = true).apply {
            background = rounded(C_S2, 9f, c)
            setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
            click {
                saveSettings { it.extend = listOf(5, 10, 15, 30)[(listOf(5, 10, 15, 30).indexOf(it.extend) + 1) % 4] }
            }
        }
        picks.addView(e)
        sheet.addView(picks)
        sheet.addView(text(c,
            "Kad zvuk počne da se stišava: pomeri telefon, pritisni dugme za jačinu ili upali ekran. Tajmer dobija još ${s.extend} min.",
            12f, C_T2).apply { setPadding(0, c.dp(16), 0, 0) })
    }

    private fun filesSheet(sheet: LinearLayout) {
        val c = this
        sheet.addView(text(c, "Pojedinačni fajlovi", 17f, C_T1, bold = true))
        sheet.addView(text(c,
            "U pravoj aplikaciji otvara se izbor fajlova, pa biraš jedan ili više njih. Gde da ih smestim?",
            12.5f, C_T2).apply { setPadding(0, c.dp(8), 0, c.dp(16)) })
        val row = horizontal(c)
        listOf(Section.BOOKS, Section.SERIES, Section.MUSIC, Section.STANDUP).forEach { sec ->
            val cell = text(c, sectionName(sec), 15f, C_T1, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(C_S2, 16f, c)
                click { applyFilesSection(sec) }
            }
            row.addView(cell, LinearLayout.LayoutParams(0, c.dp(60), 1f).apply { marginEnd = c.dp(8) })
        }
        sheet.addView(row)
    }

    private fun moveSheet(sheet: LinearLayout) {
        val c = this
        sheet.addView(text(c, "Prebaci u drugu sekciju", 17f, C_T1, bold = true))
        sheet.addView(text(c, Nav.moveName + "\ntrenutno u: ${sectionName(Nav.moveFrom)} · fajl ostaje na disku gde jeste",
            12.5f, C_T2).apply { setPadding(0, c.dp(8), 0, c.dp(16)) })
        val row = horizontal(c)
        val options = listOf(Section.BOOKS, Section.SERIES, Section.MUSIC, Section.STANDUP).filter { it != Nav.moveFrom }
        options.forEach { sec ->
            val cell = text(c, sectionName(sec), 15f, C_T1, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(C_S2, 16f, c)
                click { applyMove(sec) }
            }
            row.addView(cell, LinearLayout.LayoutParams(0, c.dp(58), 1f).apply { marginEnd = c.dp(8) })
        }
        sheet.addView(row)
        sheet.addView(text(c, "Premeštanje menja samo pripadnost sekciji; fajl se ne premešta, ne preimenuje i ne briše.",
            12f, C_T2).apply { setPadding(0, c.dp(16), 0, 0) })
    }

    private fun moveDialog(name: String, from: Section, items: List<AudioItem>, group: Group? = null) {
        Nav.moveName = name
        Nav.moveFrom = from
        Nav.moveItems = items
        Nav.sheet = "move"
        try { vibrateTap() } catch (_: Exception) { }
        render()
    }

    private fun applyMove(sec: Section) {
        Nav.moveItems.forEach { Store.setOverride(it.id, sec) }
        Bus.libraryVersion++
        Bus.log("Premešteno u ${sectionName(sec)}: ${Nav.moveName}")
        Nav.sheet = null
        Nav.moveItems = emptyList()
        render()
        toast("Premešteno u ${sectionName(sec)}: ${Nav.moveName}")
    }

    private fun vibrateTap() {
        val v = getSystemService(android.os.Vibrator::class.java)
        v.vibrate(android.os.VibrationEffect.createOneShot(18,
            android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /* ------------------------------------------------------------ noćni ekran */
    private fun nightOverlay(): View {
        val c = this
        val p = Bus.player
        val t = Bus.timer
        val navH = c.dp(58)
        val root = vertical(c).apply {
            setBackgroundColor(Color.rgb(0x05, 0x04, 0x03))
            setPadding(0, 0, 0, navH + bottomInsetPx)
        }
        val top = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20), c.dp(10), c.dp(20), c.dp(10))
            addView(text(c, "${p?.groupTitle ?: "—"} · ${p?.current?.let { LibraryManager.displayTitle(it) } ?: ""}", 12f, C_NIGHT),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconBox(c, R.drawable.ic_x, C_NIGHT, 22, 8) { Bus.night = false; render() })
        }
        root.addView(top)
        root.addView(View(c).apply { background = rounded(Color.rgb(0x23, 0x16, 0x0B), 0f, c) },
            LinearLayout.LayoutParams(-1, c.dp(1)))
        val zones = horizontal(c).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        fun zone(label: String, res: Int, iconSize: Int, action: (View) -> Unit): View =
            vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -1, if (res == R.drawable.ic_play || res == R.drawable.ic_pause) 1.3f else 1f)
                gravity = Gravity.CENTER
                if (res == R.drawable.ic_b30 || res == R.drawable.ic_f30) {
                    addView(jumpIcon(c, res == R.drawable.ic_f30, iconSize, C_NIGHT),
                        LinearLayout.LayoutParams(c.dp(iconSize), c.dp(iconSize)))
                } else addView(icon(c, res, C_NIGHT, iconSize))
                if (label.isNotBlank()) addView(text(c, label, 12.5f, C_NIGHT))
                click(action)
            }
        zones.addView(zone("−30 s", R.drawable.ic_b30, 46) { bump(-30) })
        zones.addView(zone("", if (p?.playing == true) R.drawable.ic_pause else R.drawable.ic_play, 92) { toggle() })
        zones.addView(zone("+30 s", R.drawable.ic_f30, 46) { bump(30) })
        root.addView(zones)
        if (t != null && (t.phase == TPhase.FADE || t.phase == TPhase.GRACE)) {
            root.addView(text(c, "Stišava se · pritisni + produži, protresi telefon ili pritisni dugme za jačinu",
                12f, Color.parseColor("#FFD8A3")).apply {
                background = rounded(Color.rgb(0x2A, 0x1A, 0x08), 0f, c)
                gravity = Gravity.CENTER
                setPadding(c.dp(16), c.dp(9), c.dp(16), c.dp(9))
            })
        }
        val bottom = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20), c.dp(16), c.dp(20), c.dp(20))
            val left = fmtTime(t?.let { if (it.phase == TPhase.GRACE) it.graceMs else it.leftMs } ?: 0L)
            val big = text(c, left, 30f, C_NIGHT)
            nightTimeRef = big
            addView(icon(c, R.drawable.ic_moon, C_NIGHT, 22))
            addView(big, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(10) })
            val plus = text(c, "+${Store.settings.extend} min", 16f, C_NIGHT, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = c.dp(26).toFloat()
                    setStroke(c.dp(2), C_NIGHT)
                }
                setPadding(c.dp(20), c.dp(14), c.dp(20), c.dp(14))
                click { extendTimer() }
            }
            addView(plus)
        }
        root.addView(bottom)
        return root
    }

    private fun toastView(message: String): View {
        val c = this
        return text(c, message, 12.5f, Color.rgb(0xF0, 0xE6, 0xD6)).apply {
            background = rounded(Color.rgb(0x2B, 0x24, 0x20), 12f, c)
            gravity = Gravity.CENTER
            setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10))
        }
    }

    }
