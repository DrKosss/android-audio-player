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
import android.view.WindowManager
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
    var standupTab: String = "folderi"
    var standupChips: String = "sve"
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
}

/** Tanki napredak u mini-plejeru i na skici zaključanog ekrana. */
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
    private var lockProgressRef: MiniProgress? = null
    private var timerToolRef: TextView? = null
    private var timerToastRef: TextView? = null
    private var nightTimeRef: TextView? = null
    private var searchRef: EditText? = null
    private var searchJob: Runnable? = null
    private var seekStart = 0L

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
        Store.init(this)
        requestNeededPermissions()
        if (Bus.library.items.isEmpty()) rescan()
        restoreLastPlayer()
        Bus.log("Aplikacija pokrenuta")
        render()
    }

    override fun onResume() {
        super.onResume()
        if (Bus.player != null) PlayerService.send(this, PlayerService.ACTION_SYNC)
        handler.removeCallbacks(uiTicker)
        handler.post(uiTicker)
        render()
    }

    override fun onPause() {
        handler.removeCallbacks(uiTicker)
        super.onPause()
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
                render()
            }
        }
    }

    private fun restoreLastPlayer() {
        if (Bus.player != null) return
        val o = Store.lastPlayer()
        val key = o.optString("groupKey")
        if (key.isBlank()) return
        val kind = try { Kind.valueOf(o.optString("kind")) } catch (_: Exception) { Kind.BOOK }
        val sec = when (kind) {
            Kind.BOOK -> Section.BOOKS
            Kind.EPISODE -> Section.SERIES
            Kind.TRACK -> Section.MUSIC
            Kind.STANDUP -> Section.STANDUP
        }
        val g = LibraryManager.groups(sec).find { it.key == key } ?: return
        val index = o.optInt("index", 0).coerceIn(0, g.items.lastIndex)
        Bus.player = PlayerState(
            kind, key, g.title, g.subtitle, g.items, index, coverIndex(key),
            g.title.take(1).uppercase(Locale.ROOT), g.items[index].durationMs,
            o.optLong("pos", 0), false, o.optDouble("rate", 1.0).toFloat(),
            o.optBoolean("shuffle", false), o.optBoolean("repeat", false)
        )
    }

    /* ------------------------------------------------------------ navigacija */
    private fun cur(): Screen = Nav.stack.lastOrNull() ?: Screen("books")
    private fun go(v: String, id: String = "", replace: Boolean = false) {
        if (replace && Nav.stack.isNotEmpty()) Nav.stack[Nav.stack.lastIndex] = Screen(v, id)
        else Nav.stack.add(Screen(v, id))
        Nav.sheet = null
        Bus.night = false
        Bus.lockPreview = false
        render()
    }
    private fun onBack() {
        if (Nav.stack.size > 1) Nav.stack.removeAt(Nav.stack.lastIndex)
        Nav.sheet = null
        Bus.night = false
        Bus.lockPreview = false
        render()
    }
    private fun tabHome(sec: Section) {
        Nav.tab = sec
        Nav.stack = mutableListOf(Screen(when (sec) {
            Section.BOOKS -> "books"
            Section.SERIES -> "series"
            Section.MUSIC -> "music"
            Section.STANDUP -> "standup"
            else -> "books"
        }))
        Nav.sheet = null; Bus.night = false; Bus.lockPreview = false
        render()
    }
    override fun onBackPressed() {
        when {
            Bus.lockPreview -> Bus.lockPreview = false
            Bus.night -> Bus.night = false
            Nav.sheet != null -> Nav.sheet = null
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
        if (group.items.isEmpty()) { toast("Nema audio fajlova u ovoj stavci"); return }
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
        if (p.kind == Kind.TRACK) { toast("Muzika uvek ide 1×"); return }
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
            s.v, s.id, Nav.tab.name, Nav.chips, Nav.musicTab, Nav.seriesTab,
            Nav.standupTab, Nav.standupChips,
            Nav.sheet ?: "", Nav.feedId, Nav.catalog.size, Nav.catalogItem?.title ?: "",
            Nav.importSelection.size, Nav.scanning, Nav.moveName, Nav.moveFrom.name,
            Bus.night, Bus.lockPreview, Bus.libraryVersion, Bus.toast ?: "",
            p?.playing, p?.groupKey, p?.index, p?.rate, p?.durationMs,
            t?.phase?.name ?: "off", Bus.undo != null,
            st.jump, st.fade, st.extend, st.gShake, st.gVol, st.gScreen,
            st.nextAuto, st.covers, st.autodl, st.wifiOnly, st.timeSpeed,
            Store.feeds().size, Store.hasAnyFolder()
        ).joinToString("|")
    }

    private fun render() {
        val c = this
        seekRef = null; timeLeftRef = null; timeRightRef = null; wholeRef = null
        titleRef = null; miniProgressRef = null; lockProgressRef = null
        timerToolRef = null; timerToastRef = null; nightTimeRef = null; searchRef = null

        val root = FrameLayout(c).apply { setBackgroundColor(C_BG) }
        val base = vertical(c)
        base.addView(statusBar())
        base.addView(screenRoot(), LinearLayout.LayoutParams(-1, 0, 1f))
        base.addView(homeIndicator())
        root.addView(base, FrameLayout.LayoutParams(-1, -1))

        val homeScreens = setOf("books", "series", "music", "standup", "folder", "seriesdetail", "feed", "feeds", "catitem", "import", "arrange", "mixed", "settings", "log", "battery")
        if (Nav.sheet == null && !Bus.night && !Bus.lockPreview && cur().v in homeScreens) {
            root.addView(helpFab(), FrameLayout.LayoutParams(c.dp(40), c.dp(40),
                Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = c.dp(74); rightMargin = c.dp(14)
            })
        }
        if (Nav.sheet != null) root.addView(sheetOverlay(), FrameLayout.LayoutParams(-1, -1))
        if (Bus.night) root.addView(nightOverlay(), FrameLayout.LayoutParams(-1, -1))
        if (Bus.lockPreview) root.addView(lockOverlay(), FrameLayout.LayoutParams(-1, -1))
        Bus.toast?.let {
            if (System.currentTimeMillis() < Bus.toastUntil) {
                val bottom = if (Bus.player != null && !Bus.night && !Bus.lockPreview) c.dp(132) else c.dp(74)
                root.addView(toastView(it), FrameLayout.LayoutParams(-1, -2,
                    Gravity.BOTTOM).apply { leftMargin = c.dp(16); rightMargin = c.dp(16); bottomMargin = bottom })
            } else Bus.toast = null
        }
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
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
        wholeRef?.text = when (p.kind) {
            Kind.BOOK -> "Cela knjiga: ${(f * 100).roundToInt()}% · preostalo ${fmtShort(dur - p.positionMs)}"
            Kind.STANDUP -> "Ceo snimak: ${(f * 100).roundToInt()}% · preostalo ${fmtShort(dur - p.positionMs)}"
            else -> "ukupno ${fmtShort(dur)}"
        }
        titleRef?.text = p.current.title
        miniProgressRef?.set(f)
        lockProgressRef?.set(f)
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

    private fun homeIndicator(): View {
        val c = this
        return FrameLayout(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(18))
            val bar = View(c).apply { background = rounded(Color.rgb(0x3A, 0x36, 0x32), 2f, c) }
            addView(bar, FrameLayout.LayoutParams(c.dp(96), c.dp(4), Gravity.CENTER))
        }
    }

    private fun header(title: String, plus: Boolean = true, gear: Boolean = true,
                       back: Boolean = false): View {
        val c = this
        return horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (back) c.dp(8) else c.dp(18), c.dp(6), c.dp(18), c.dp(10))
            if (back) addView(iconBox(c, R.drawable.ic_back, C_T2, 22, 6) { onBack() })
            addView(text(c, title, if (back) 20f else 26f, C_T1, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            if (plus) addView(iconBox(c, R.drawable.ic_plus, C_T2, 22, 6) { go("import") })
            if (gear) addView(iconBox(c, R.drawable.ic_settings, C_T2, 22, 6) { go("settings") })
        }
    }

    private fun chipsBar(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val c = this
        val inner = horizontal(c).apply { setPadding(c.dp(16), 0, c.dp(16), c.dp(10)) }
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
                    setPadding(0, c.dp(6), 0, c.dp(10))
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
        card.addView(cover(c, p.cover, p.initial, 38, 9f))
        val tx = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(10) }
            addView(text(c, p.groupTitle, 14f, C_T1))
            val timerPart = Bus.timer?.let {
                " · tajmer " + fmtTime(if (it.phase == TPhase.GRACE) it.graceMs else it.leftMs)
            } ?: ""
            addView(text(c, p.current.title + timerPart, 12f, C_T2))
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
        val wrap = vertical(c).apply { layoutParams = LinearLayout.LayoutParams(-1, -2) }
        wrap.addView(View(c).apply { background = rounded(C_LINE, 0f, c) },
            LinearLayout.LayoutParams(-1, c.dp(1)))
        val row = horizontal(c).apply { layoutParams = LinearLayout.LayoutParams(-1, c.dp(66)) }
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
                addView(icon(c, res, color, 30))
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
        "import" -> importScreen()
        "mixed" -> mixedScreen()
        "feeds" -> feedsScreen()
        "feed" -> feedScreen(cur().id)
        "catitem" -> catalogItemScreen()
        "log" -> logScreen()
        "battery" -> batteryScreen()
        else -> booksScreen()
    }

    private fun booksScreen(): View {
        val c = this
        Nav.tab = Section.BOOKS
        val groups = LibraryManager.groups(Section.BOOKS)
        val filtered = groups.filter { g ->
            val pos = LibraryManager.bookPosition(g)
            val done = Store.isDone(g.key) || pos >= g.durationMs * 0.98 && g.durationMs > 0
            when (Nav.chips) {
                "tok" -> pos > 0 && !done
                "nove" -> pos == 0L
                "zav" -> done
                else -> true
            }
        }
        val list = listHost()
        if (filtered.isEmpty()) list.addView(emptyState("Nema knjiga u ovoj grupi.\nUvezi foldere sa audio fajlovima.",
            "Uvezi foldere") { go("import") })
        filtered.forEach { g ->
            val pos = LibraryManager.bookPosition(g)
            val done = Store.isDone(g.key) || pos >= g.durationMs * 0.98 && g.durationMs > 0
            val sub = when {
                done -> "${g.subtitle} · završena"
                pos > 0 -> "${g.subtitle} · preostalo ${fmtShort(g.durationMs - pos)}"
                else -> "${g.subtitle} · ${fmtShort(g.durationMs)} · nova"
            }
            val row = rowView(c, { openBook(g) }, {
                moveDialog(g.title, Section.BOOKS, g.items); true
            })
            row.addView(cover(c, g.cover, g.initial, 48, 11f))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, g.title, 14.5f, C_T1))
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
            scroll(c, list), miniPlayer(), navBar()
        )
    }

    private fun seriesScreen(): View {
        val c = this
        Nav.tab = Section.SERIES
        val tabs = tabsBar(listOf("folderi" to "Folderi", "feedovi" to "Feedovi", "katalog" to "Katalog"),
            Nav.seriesTab) { Nav.seriesTab = it }
        val list = listHost()
        when (Nav.seriesTab) {
            "folderi" -> {
                val groups = LibraryManager.groups(Section.SERIES)
                if (groups.isEmpty()) list.addView(emptyState("Nema serija. Uvezi foldere sa epizodama.",
                    "Uvezi foldere") { go("import") })
                groups.forEach { g ->
                    val fresh = g.items.count { LibraryManager.episodeStatus(it) == "new" }
                    val started = g.items.any { LibraryManager.episodeStatus(it) == "started" }
                    val row = rowView(c, { go("seriesdetail", g.key) }, {
                        moveDialog(g.title, Section.SERIES, g.items); true
                    })
                    row.addView(cover(c, g.cover, g.initial, 48, 11f))
                    val tx = vertical(c).apply {
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                        addView(text(c, g.title, 14.5f, C_T1))
                        addView(text(c, "${g.items.size} epizoda" +
                            (if (fresh > 0) " · $fresh nove" else "") + if (started) " · u toku" else "", 12f, C_T2))
                    }
                    row.addView(tx)
                    row.addView(icon(c, R.drawable.ic_right, C_T3, 16))
                    list.addView(row)
                }
                val importRow = rowView(c, { go("import") })
                importRow.addView(FrameLayout(c).apply {
                    layoutParams = LinearLayout.LayoutParams(c.dp(56), c.dp(56))
                    background = rounded(C_S2, 12f, c)
                    addView(icon(c, R.drawable.ic_plus, C_AMBER, 30),
                        FrameLayout.LayoutParams(c.dp(30), c.dp(30), Gravity.CENTER))
                })
                importRow.addView(vertical(c).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                    addView(text(c, "Uvezi foldere ili fajlove", 14.5f, C_T1))
                    addView(text(c, "interna memorija, SD kartica", 12f, C_T2))
                })
                list.addView(importRow)
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
        return page(header("Emisije"), tabs, scroll(c, list), miniPlayer(), navBar())
    }

    /* ------------------------------------------------------------ Četvrta sekcija: Stand up */
    private fun standupScreen(): View {
        val c = this
        Nav.tab = Section.STANDUP
        val tabs = tabsBar(listOf("folderi" to "Folderi", "komičari" to "Komičari"),
            Nav.standupTab) { Nav.standupTab = it }
        val chips = chipsBar(listOf("sve" to "Sve", "tok" to "U toku", "nove" to "Nove", "zav" to "Završeni"),
            Nav.standupChips) { Nav.standupChips = it }
        val groups = LibraryManager.groups(Section.STANDUP, Nav.standupTab)
        val filtered = groups.filter { g ->
            val pos = LibraryManager.bookPosition(g)
            val done = Store.isDone(g.key) || pos >= g.durationMs * 0.98 && g.durationMs > 0
            when (Nav.standupChips) {
                "tok" -> pos > 0 && !done
                "nove" -> pos == 0L
                "zav" -> done
                else -> true
            }
        }
        val list = listHost()
        if (filtered.isEmpty()) list.addView(emptyState(
            "Nema stand up snimaka u ovoj grupi.\nUvezi foldere sa nastupima.",
            "Uvezi foldere") { go("import") })
        filtered.forEach { g ->
            val pos = LibraryManager.bookPosition(g)
            val done = Store.isDone(g.key) || pos >= g.durationMs * 0.98 && g.durationMs > 0
            val sub = when {
                done -> "${g.subtitle} · odgledano"
                pos > 0 -> "${g.subtitle} · preostalo ${fmtShort(g.durationMs - pos)}"
                else -> "${g.subtitle} · ${fmtShort(g.durationMs)} · novo"
            }
            val row = rowView(c, { openStandup(g) }, {
                moveDialog(g.title, Section.STANDUP, g.items); true
            })
            row.addView(cover(c, g.cover, g.initial, 56, 12f))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, g.title, 14.5f, C_T1))
                addView(text(c, sub, 12f, C_T2))
                if (pos > 0 && !done && g.durationMs > 0)
                    addView(progressLine(c, pos.toFloat() / g.durationMs))
            }
            row.addView(tx)
            if (done) row.addView(icon(c, R.drawable.ic_check, C_T2, 18))
            if (done) row.alpha = 0.42f
            list.addView(row)
        }
        val importRow = rowView(c, { go("import") })
        importRow.addView(folderIconView(c, Section.STANDUP, 30, 56))
        importRow.addView(vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
            addView(text(c, "Uvezi stand up foldere", 14.5f, C_T1))
            addView(text(c, "interna memorija, SD kartica", 12f, C_T2))
        })
        list.addView(importRow)
        return page(header("Stand up"), tabs, chips, scroll(c, list), miniPlayer(), navBar())
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
            val row = rowView(c, { openEpisode(g, i) }, {
                moveDialog(item.title, Section.SERIES, listOf(item), g); true
            })
            val status = FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(22), c.dp(22))
                when (st) {
                    "done" -> addView(icon(c, R.drawable.ic_check, C_T2, 16),
                        FrameLayout.LayoutParams(c.dp(16), c.dp(16), Gravity.CENTER))
                    "started" -> addView(View(c).apply {
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
                addView(text(c, item.title, 14.5f, C_T1))
                if (st == "started") addView(progressLine(c,
                    Store.position(item.id).toFloat() / item.durationMs.coerceAtLeast(1)))
            }
            row.addView(tx)
            val pos = Store.position(item.id)
            row.addView(text(c, if (st == "started") "još ${((item.durationMs - pos) / 60000)} min"
            else "${(item.durationMs / 60000)} min", 12f, C_T2))
            if (st == "done") row.alpha = 0.42f
            list.addView(row)
        }
        return page(header(g.title, plus = false, gear = false, back = true), actions, note,
            scroll(c, list), miniPlayer())
    }

    private fun musicScreen(): View {
        val c = this
        Nav.tab = Section.MUSIC
        val tabs = tabsBar(listOf("folderi" to "Folderi", "izvodjaci" to "Izvođači", "albumi" to "Albumi"),
            Nav.musicTab) { Nav.musicTab = it }
        val actions = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), 0, c.dp(16), c.dp(10))
            addView(actionButton(c, "Nasumično sve", false, R.drawable.ic_shuffle) {
                val all = LibraryManager.groups(Section.MUSIC).flatMap { it.items }.shuffled()
                if (all.isEmpty()) toast("Nema muzike. Uvezi foldere.")
                else playItems(Kind.TRACK, "sve", "Sve numere", "Nasumično", all, 0,
                    shuffle = true, repeat = Store.settings.repeat)
            }, LinearLayout.LayoutParams(0, c.dp(42), 1f))
        }
        val list = listHost()
        val groups = LibraryManager.groups(Section.MUSIC, Nav.musicTab)
        if (groups.isEmpty()) list.addView(emptyState("Nema muzike u ovoj sekciji.\nUvezi foldere sa numerama.",
            "Uvezi foldere") { go("import") })
        groups.forEach { g ->
            val row = rowView(c, { go("folder", g.key) }, {
                moveDialog(g.title, Section.MUSIC, g.items); true
            })
            row.addView(cover(c, g.cover, g.initial, 48, 11f))
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, g.title, 14.5f, C_T1))
                addView(text(c, "${g.items.size} numera", 12f, C_T2))
            }
            row.addView(tx)
            row.addView(icon(c, R.drawable.ic_right, C_T3, 16))
            list.addView(row)
        }
        val shuffleRow = settingRow(c, "Nasumično", null, Store.settings.shuffle) {
            val s = Store.settings; s.shuffle = !s.shuffle; Store.settings = s
            toast(if (s.shuffle) "Nasumični red uključen" else "Nasumični red isključen")
        }
        val repeatRow = settingRow(c, "Ponavljanje", null, Store.settings.repeat) {
            val s = Store.settings; s.repeat = !s.repeat; Store.settings = s
            toast(if (s.repeat) "Ponavljanje uključeno" else "Ponavljanje isključeno")
        }
        list.addView(shuffleRow); list.addView(repeatRow)
        return page(header("Muzika"), tabs, actions, scroll(c, list), miniPlayer(), navBar())
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
            val row = rowView(c, { openTrack(g, i) }, {
                moveDialog(item.title, Section.MUSIC, listOf(item), g); true
            })
            val tx = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                addView(text(c, item.title, 14.5f, C_T1))
                addView(text(c, "${g.title} · ${fmtTime(item.durationMs)}", 12f, C_T2))
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
            emptyState("Ništa se ne pušta.\nOtvori knjigu, epizodu ili numeru."), navBar())
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setPadding(c.dp(22), 0, c.dp(22), c.dp(6))
        }
        val top = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(34))
            gravity = Gravity.CENTER_VERTICAL
            addView(iconBox(c, R.drawable.ic_down, C_T2, 22, 4) { onBack() })
            addView(text(c, p.sectionLabel, 10.5f, C_T2, bold = true).apply {
                letterSpacing = 0.16f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(iconBox(c, R.drawable.ic_lock, C_T2, 22, 4) { Bus.lockPreview = true; render() })
        }
        body.addView(top)
        body.addView(playerArt(p))
        titleRef = text(c, p.current.title, 18f, C_T1, bold = true)
        body.addView(titleRef)
        body.addView(text(c, p.groupTitle + " · ${p.index + 1} od ${p.queue.size}", 12.5f, C_T2).apply {
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
            l.click { gotoDialog() }
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
                when (p.kind) { Kind.BOOK -> "Poglavlja"; Kind.STANDUP -> "Delovi"; else -> "Lista" },
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
        val bmp = Bus.covers[p.groupKey] ?: LibraryManager.embeddedCover(c, p.current)?.also {
            Bus.covers[p.groupKey] = it
        }
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
        if (p.index + 1 < p.queue.size) "Sledeće: ${p.queue[p.index + 1].title} · ${(p.queue[p.index + 1].durationMs / 60000)} min"
        else "Poslednje u redu"

    private fun gotoDialog() {
        val p = Bus.player ?: return
        val c = this
        val input = EditText(c).apply {
            hint = "HH:MM:SS ili MM:SS"
            setText(fmtTime(p.positionMs))
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(C_T1)
        }
        val pad = FrameLayout(c).apply {
            setPadding(c.dp(20), c.dp(12), c.dp(20), 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(c)
            .setTitle("Idi na vreme")
            .setView(pad)
            .setPositiveButton("Idi") { _, _ ->
                val parts = input.text.toString().split(':').map { it.trim().toLongOrNull() ?: 0L }
                val ms = when (parts.size) {
                    3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                    2 -> (parts[0] * 60 + parts[1]) * 1000L
                    else -> 0L
                }
                seekTo(ms); Bus.undo = p.positionMs to System.currentTimeMillis(); render()
            }
            .setNegativeButton("Otkaži", null)
            .show()
    }

    /* ------------------------------------------------------------ poglavlja / lista */
    private fun chaptersScreen(): View {
        val c = this
        val p = Bus.player ?: return page(header("Poglavlja", back = true), emptyState("Ništa se ne pušta."))
        val list = listHost()
        if (isBookLike(p.kind) && p.queue.size == 1) {
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
                })
                row.addView(statusDot(c, here))
                row.addView(vertical(c).apply {
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    addView(text(c, item.title, 14.5f, if (here) C_AMBER else C_T1))
                    addView(text(c, fmtTime(offset), 12f, C_T2))
                })
                row.addView(text(c, fmtTime(item.durationMs), 12f, C_T2))
                offset += item.durationMs
                list.addView(row)
            }
        }
        val title = when (p.kind) { Kind.BOOK -> "Poglavlja"; Kind.STANDUP -> "Delovi"; else -> "Lista" }
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
        if (!Store.settings.covers || Bus.covers.containsKey(p.groupKey)) return
        val embedded = LibraryManager.embeddedCover(this, p.current)
        if (embedded != null) { Bus.covers[p.groupKey] = embedded; return }
        if (!coverRequested.add(p.groupKey)) return
        val query = "${p.groupTitle} ${p.sub}".trim()
        thread {
            val url = Net.coverUrl(query)
            val bmp = url?.let { Net.bitmap(it) }
            if (bmp != null) runOnUiThread { Bus.covers[p.groupKey] = bmp; render() }
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
        val unassigned = Bus.library.folders.count { Store.folderSection(it.key) == null }
        body.addView(sectionLabelView("FOLDERI"))
        val importRow = settingRow(c, "+ Uvezi foldere i fajlove", "$unassigned novih") { go("import") }
        (importRow.getChildAt(0) as? TextView)?.setTextColor(C_AMBER)
        body.addView(importRow)
        Bus.library.folders.take(12).forEach { f ->
            val sec = Store.folderSection(f.key) ?: f.suggested
            val row = rowView(c, { cycleFolder(f.key); render() })
            row.addView(folderIconView(c, sec, 26, 48))
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, f.name, 14.5f, C_T1))
                addView(text(c, "${f.count} fajlova", 12f, C_T2))
            })
            val badge = text(c, sectionName(sec), 11.5f,
                if (sec == null) C_T3 else C_AMBER, bold = true)
            badge.background = if (sec == null)
                GradientDrawable().apply {
                    cornerRadius = c.dp(9).toFloat()
                    setColor(Color.TRANSPARENT)
                    setStroke(c.dp(1), Color.rgb(0x3B, 0x35, 0x30))
                } else rounded(C_AMBER_D, 9f, c)
            badge.setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
            row.addView(badge)
            body.addView(row)
        }
        body.addView(settingRow(c, "Rasporedi fajlove", "$unassigned čekaju") { go("arrange") })
        body.addView(settingRow(c, "Mešani fajlovi", "${LibraryManager.mixed().size} sumnjivih") { go("mixed") })
        body.addView(settingRow(c, "Podkast feedovi",
            "${Store.feeds().size} · ${if (s.wifiOnly) "samo Wi-Fi" else "i mobilni"}") { go("feeds") })

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
        body.addView(settingRow(c, "Preuzimaj nove epizode", null, s.autodl) {
            saveSettings { it.autodl = !it.autodl }
        })
        body.addView(settingRow(c, "Preuzimanja samo na Wi-Fi", null, s.wifiOnly) {
            saveSettings { it.wifiOnly = !it.wifiOnly }
        })

        body.addView(sectionLabelView("RAD U POZADINI"))
        body.addView(settingRow(c, "Baterija bez ograničenja", ok = true) { go("battery") })
        body.addView(settingRow(c, "Izuzeta od uspavljivanja", ok = true) { go("battery") })
        body.addView(settingRow(c, "Dnevnik događaja", "—") { go("log") })
        body.addView(text(c,
            "Šaptač v1.0.1 · podaci ostaju na telefonu. Za pouzdan rad sa ugašenim ekranom podesi bateriju po vodiču.",
            11.5f, C_T3).apply { setPadding(0, c.dp(18), c.dp(4), c.dp(6)) })
        return page(header("Podešavanja", plus = false, gear = false, back = true), scroll(c, body))
    }

    private fun cycleFolder(key: String) {
        val order = listOf<Section?>(null, Section.MUSIC, Section.BOOKS, Section.SERIES, Section.STANDUP)
        val cur = Store.folderSection(key)
        val i = order.indexOf(cur).let { if (it < 0) 0 else it }
        Store.setFolderSection(key, order[(i + 1) % order.size])
        Bus.libraryVersion++
        Bus.log("Folder ${key.substringAfterLast('/')} → ${sectionName(Store.folderSection(key))}")
    }

    /* ------------------------------------------------------------ raspoređivanje i uvoz */
    private fun arrangeScreen(): View {
        val c = this
        val actions = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(16), 0, c.dp(16), c.dp(10))
            addView(actionButton(c, "Prihvati predloge", true) {
                LibraryManager.acceptSuggestions(); toast("Predlozi prihvaćeni")
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
            val sec = Store.folderSection(f.key) ?: f.suggested
            val row = rowView(c, { cycleFolder(f.key); render() })
            row.addView(folderIconView(c, sec, 30, 56))
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, f.name, 14.5f, C_T1))
                addView(text(c, "${f.count} fajlova", 12f, C_T2))
            })
            val badge = text(c, sectionName(sec), 11.5f, if (sec == null) C_T3 else C_AMBER, bold = true)
            badge.background = if (sec == null) GradientDrawable().apply {
                cornerRadius = c.dp(9).toFloat(); setColor(Color.TRANSPARENT)
                setStroke(c.dp(1), Color.rgb(0x3B, 0x35, 0x30))
            } else rounded(C_AMBER_D, 9f, c)
            badge.setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
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

    private fun importScreen(): View {
        val c = this
        if (!hasReadPermission() && !Environment.isExternalStorageManager()) {
            val body = vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                setPadding(c.dp(22), c.dp(10), c.dp(22), c.dp(24))
                addView(folderIconView(c, null, 36, 64).apply {
                    (layoutParams as LinearLayout.LayoutParams).bottomMargin = c.dp(16)
                })
                addView(text(c, "Šaptač traži pristup fajlovima", 19f, C_T1, bold = true))
                addView(text(c,
                    "Da bi čitao muziku, knjige i emisije sa telefona i SD kartice, potrebna je dozvola „Pristup svim fajlovima“. Aplikacija ništa ne menja i ništa ne briše; samo čita ono što joj ti pokažeš.",
                    13.5f, C_T2).apply { setPadding(0, c.dp(8), 0, c.dp(14)) })
                addView(text(c,
                    "Aplikacija nije iz prodavnice, pa ne može da traži dozvolu pojedinačno po folderu. Možeš i samo da dozvoliš pristup audio fajlovima.",
                    12f, C_T3).apply { setPadding(0, 0, 0, c.dp(22)) })
                addView(actionButton(c, "Dozvoli pristup svim fajlovima", true) { openAllFilesAccess() })
                addView(actionButton(c, "Dozvoli pristup audio fajlovima", false) { requestNeededPermissions() }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, c.dp(44)).apply { topMargin = c.dp(8) }
                })
                addView(actionButton(c, "Ne sad", false) { onBack() }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, c.dp(44)).apply { topMargin = c.dp(8) }
                })
            }
            return page(header("Uvoz", plus = false, gear = false, back = true), scroll(c, body))
        }
        if (Nav.scanning) {
            return page(header("Uvoz", plus = false, gear = false, back = true),
                emptyState("Pregledam telefon…\nInterna memorija i SD kartica."))
        }
        val folders = Bus.library.folders
        val list = listHost()
        if (folders.isEmpty()) list.addView(emptyState("Nije pronađen nijedan folder sa audio fajlovima.\nMožeš izabrati folder ručno."))
        folders.forEach { f ->
            val included = Nav.importSelection.contains(f.key)
            if (!Nav.importSections.containsKey(f.key)) Nav.importSections[f.key] = f.suggested
            val sec = Nav.importSections[f.key]
            val row = rowView(c, {
                if (included) Nav.importSelection.remove(f.key) else Nav.importSelection.add(f.key)
                render()
            })
            val check = android.widget.CheckBox(c).apply {
                isChecked = included
                layoutParams = LinearLayout.LayoutParams(c.dp(24), c.dp(24))
                buttonTintList = android.content.res.ColorStateList.valueOf(C_AMBER)
                setOnClickListener {
                    if (isChecked) Nav.importSelection.add(f.key) else Nav.importSelection.remove(f.key)
                    render()
                }
            }
            row.addView(check)
            row.addView(folderIconView(c, sec, 24, 44))
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(10) }
                addView(text(c, f.name, 14.5f, C_T1))
                addView(text(c, "${f.count} fajlova · ${f.key}", 11f, C_T3))
            })
            val badge = text(c, sectionName(sec), 11.5f, if (sec == null) C_T3 else C_AMBER, bold = true)
            badge.background = if (sec == null) GradientDrawable().apply {
                cornerRadius = c.dp(9).toFloat(); setColor(Color.TRANSPARENT)
                setStroke(c.dp(1), Color.rgb(0x3B, 0x35, 0x30))
            } else rounded(C_AMBER_D, 9f, c)
            badge.setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
            badge.click {
                val order = listOf<Section?>(null, Section.BOOKS, Section.SERIES, Section.MUSIC, Section.STANDUP)
                val cur = Nav.importSections[f.key]
                val i = order.indexOf(cur).let { if (it < 0) 0 else it }
                Nav.importSections[f.key] = order[(i + 1) % order.size]
                Nav.importSelection.add(f.key)
                render()
            }
            row.addView(badge)
            list.addView(row)
        }
        val bottom = vertical(c).apply {
            setPadding(c.dp(16), c.dp(8), c.dp(16), c.dp(10))
            addView(actionButton(c,
                if (Nav.importSelection.isEmpty()) "Izaberi najmanje jedan folder"
                else "Uvezi ${Nav.importSelection.size} foldera", true) { doImport() })
            addView(horizontal(c).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(8) }
                addView(actionButton(c, "Pojedinačni fajlovi", false) { importFiles() },
                    LinearLayout.LayoutParams(0, c.dp(42), 1f))
                addView(actionButton(c, "Folder sa SD kartice", false) { importTree() },
                    LinearLayout.LayoutParams(0, c.dp(42), 1f).apply { marginStart = c.dp(8) })
            })
            addView(text(c,
                "Fajlovi ostaju gde jesu. Uvoz samo pamti koji folder pripada kojoj sekciji, pa ništa ne može da se izgubi ili obriše.",
                11.5f, C_T3).apply { setPadding(0, c.dp(12), 0, 0) })
        }
        return page(header("Uvoz", plus = false, gear = false, back = true), scroll(c, list), bottom)
    }

    private fun doImport() {
        if (Nav.importSelection.isEmpty()) { toast("Izaberi najmanje jedan folder"); return }
        Nav.importSelection.forEach { key -> Store.setFolderSection(key, Nav.importSections[key]) }
        Store.importedOnce = true
        Bus.libraryVersion++
        val wait = Nav.importSelection.count { Nav.importSections[it] == null }
        Nav.importSelection.clear()
        Bus.log("Uvezeno foldera: ${Bus.library.folders.size}")
        if (wait > 0) { go("arrange"); toast("$wait foldera čeka raspoređivanje", 2600) }
        else { onBack(); toast("Uvoz završen") }
    }

    private fun importTree() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 2001)
    }

    private fun importFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(i, 2002)
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
        rescan()
        toast("Dodato u ${sectionName(sec)}")
    }

    private fun openAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
            else openAppSettings()
        } catch (_: Exception) { openAppSettings() }
    }
    private fun openAppSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"))) } catch (_: Exception) { }
    }
    private fun openBatterySettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: Exception) { openAppSettings() }
    }

    /* ------------------------------------------------------------ mešani fajlovi */
    private fun mixedScreen(): View {
        val c = this
        val list = listHost()
        val items = LibraryManager.mixed()
        if (items.isEmpty()) list.addView(emptyState("Nema sumnjivih fajlova."))
        items.forEach { (item, looks) ->
            val row = rowView(c, {
                Store.setOverride(item.id, looks)
                Bus.libraryVersion++
                Bus.log("Mešani fajl premešten: ${item.title} → ${sectionName(looks)}")
                toast("Premešteno u ${sectionName(looks)}: ${item.title}")
            })
            row.addView(folderIconView(c, looks, 30, 56))
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, item.title, 14.5f, C_T1))
                addView(text(c, "u folderu ${item.folderName} · ${fmtShort(item.durationMs)}", 12f, C_T2))
            })
            val badge = text(c, "→ ${sectionName(looks)}", 11.5f, C_AMBER, bold = true)
            badge.background = rounded(C_AMBER_D, 9f, c)
            badge.setPadding(c.dp(11), c.dp(6), c.dp(11), c.dp(6))
            row.addView(badge)
            list.addView(row)
        }
        val footer = text(c,
            "Aplikacija prati dužinu, ime i tagove. Fajl od 50 minuta u folderu muzike koji se zove „Glava 3“ verovatno je deo knjige. Dodir premešta, dug pritisak otvara isti izbor kao u listama.",
            11.5f, C_T3).apply { setPadding(c.dp(20), c.dp(6), c.dp(20), c.dp(20)) }
        return page(header("Mešani fajlovi", back = true),
            text(c, "${items.size} sumnjivih · predlog, ne pravilo", 12f, C_T2).apply {
                setPadding(c.dp(18), c.dp(4), c.dp(18), c.dp(8))
            }, scroll(c, list), footer)
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
            val row = rowView(c, { playFeedEpisode(f, i) })
            row.addView(FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(42), c.dp(42))
                background = rounded(C_S2, 11f, c)
                addView(icon(c, if (local) R.drawable.ic_check else R.drawable.ic_down, C_T2, 19),
                    FrameLayout.LayoutParams(c.dp(19), c.dp(19), Gravity.CENTER))
            })
            row.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(12) }
                addView(text(c, ep.title, 14.5f, C_T1))
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
            toast("Preuzimanja su podešena samo na Wi-Fi"); return
        }
        toast("Preuzimam epizodu…", 4000)
        thread {
            val safeId = f.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(filesDir, "podcasts/${safeId}_${index}.mp3")
            val ok = Net.download(ep.url, file) { }
            runOnUiThread {
                if (!ok) { toast("Preuzimanje nije uspelo"); return@runOnUiThread }
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
            size = file.length(), added = file.lastModified(), track = 0
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
                if (!url.startsWith("http")) { toast("Unesi punu https adresu"); return@setPositiveButton }
                toast("Čitam feed…", 4000)
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
                            toast("Feed dodat: $title")
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Ne mogu da pročitam feed: ${e.message}") }
                    }
                }
            }.setNegativeButton("Otkaži", null).show()
    }

    private fun refreshOneFeed(id: String) = refreshFeeds(listOf(id))

    private fun refreshFeeds(only: List<String>? = null) {
        toast("Osvežavam feedove…", 4000)
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
            runOnUiThread { Bus.log("Feedovi osveženi"); render(); toast("Feedovi osveženi") }
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
            toast("Demo katalog nema RSS adresu. Potraži emisiju na mreži.")
            return
        }
        toast("Zapraćujem…", 4000)
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
                runOnUiThread { toast("Ne mogu da pročitam feed: ${e.message}") }
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

    /* ------------------------------------------------------------ katalog / dnevnik / baterija */
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
                if (item.feedUrl.isBlank()) toast("Demo katalog nema RSS adresu")
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

    private fun logScreen(): View {
        val c = this
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(18), 0, c.dp(18), c.dp(16))
        }
        val logs = Store.logs()
        if (logs.isEmpty()) body.addView(emptyState("Dnevnik je prazan."))
        logs.forEach { l ->
            body.addView(text(c, l, 12f, C_T2).apply {
                setPadding(0, c.dp(7), 0, c.dp(7))
                typeface = Typeface.MONOSPACE
            })
            body.addView(View(c).apply { background = rounded(C_S2, 0f, c) },
                LinearLayout.LayoutParams(-1, c.dp(1)))
        }
        return page(header("Dnevnik događaja", back = true),
            actionButton(c, "Obriši dnevnik", false) { Store.clearLog(); render() }.apply {
                layoutParams = LinearLayout.LayoutParams(-1, c.dp(42)).apply {
                    setMargins(c.dp(16), 0, c.dp(16), c.dp(10))
                }
            }, scroll(c, body))
    }

    private fun batteryScreen(): View {
        val c = this
        val body = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(c.dp(20), c.dp(6), c.dp(20), c.dp(20))
        }
        body.addView(text(c, "Da zvuk ne stane kad se ekran ugasi, podesi tri stvari na telefonu jednom.",
            14f, C_T1))
        val steps = listOf(
            "Podešavanja → Aplikacije → Šaptač → Baterija → Neograničeno",
            "Podešavanja → Baterija → Ograničenja rada u pozadini → dodaj Šaptač u „Nikad ne uspavljuj“",
            "Dozvoli obaveštenja kada aplikacija pita."
        )
        steps.forEachIndexed { i, st ->
            body.addView(text(c, "${i + 1}.  $st", 13.5f, C_T2).apply {
                setPadding(0, c.dp(16), 0, 0)
            })
        }
        body.addView(text(c,
            "Reprodukcija radi u sistemskom servisu za medije sa stalnim obaveštenjem. Gašenje ekrana, zaključavanje i uklanjanje iz liste nedavnih ne prekidaju zvuk.",
            12.5f, C_T3).apply { setPadding(0, c.dp(22), 0, c.dp(18)) })
        body.addView(actionButton(c, "Otvori podešavanja aplikacije", true) { openAppSettings() })
        body.addView(actionButton(c, "Podešavanja baterije", false) { openBatterySettings() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, c.dp(44)).apply { topMargin = c.dp(8) }
        })
        return page(header("Rad u pozadini", back = true), scroll(c, body))
    }

    /* ------------------------------------------------------------ listići (sheet) */
    private fun sheetOverlay(): View {
        val c = this
        val scrim = FrameLayout(c).apply {
            background = android.graphics.drawable.ColorDrawable(Color.argb(184, 0, 0, 0))
            click { Nav.sheet = null; render() }
        }
        val sheet = vertical(c).apply {
            background = GradientDrawable().apply {
                setColor(C_S1)
                cornerRadii = floatArrayOf(
                    c.dp(26).toFloat(), c.dp(26).toFloat(), c.dp(26).toFloat(), c.dp(26).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(c.dp(1), C_LINE)
            }
            setPadding(c.dp(18), c.dp(10), c.dp(18), c.dp(26))
            click { }
            addView(View(c).apply { background = rounded(Color.rgb(0x3A, 0x36, 0x32), 2f, c) },
                LinearLayout.LayoutParams(c.dp(36), c.dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = c.dp(16)
                })
        }
        when (Nav.sheet) {
            "timer" -> timerSheet(sheet)
            "help" -> helpSheet(sheet)
            "files" -> filesSheet(sheet)
            "move" -> moveSheet(sheet)
        }
        scrim.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
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

    private fun helpSheet(sheet: LinearLayout) {
        val c = this
        sheet.addView(text(c, "Kako da probaš", 17f, C_T1, bold = true).apply {
            setPadding(0, 0, 0, c.dp(14))
        })
        listOf(
            "Knjige, Muzika, Emisije i Stand up — donja traka menja sekciju.",
            "Dodir na knjigu otvara plejer; na dnu liste je mini-plejer.",
            "Na plejeru su alati (Tajmer, Brzina, Lista) ispod trake, a transport (−30, pusti, +30) na dnu.",
            "Prethodno prvo vraća na početak dela; drugi dodir prebacuje na prethodni fajl.",
            "Tajmer na plejeru vodi na noćni ekran. Poslednjih 60 s zvuk se stišava.",
            "Uvoz: dugme + gore desno u svakoj sekciji, ili prvi red u Podešavanjima.",
            "Pogrešna sekcija: dug pritisak na stavku otvara „Prebaci u…“.",
            "Emisije → Katalog: traži podkaste po imenu (Apple katalog)."
        ).forEach { line ->
            sheet.addView(text(c, "•  $line", 13f, C_T2).apply {
                setPadding(0, 0, 0, c.dp(8))
            })
        }
        sheet.addView(text(c, "Zvuk ide u sistemskom servisu, pa radi i kad je ekran ugašen.",
            12f, C_T3).apply { setPadding(0, c.dp(10), 0, 0) })
    }

    private fun filesSheet(sheet: LinearLayout) {
        val c = this
        val title = if (Nav.filesTarget == "tree") "Izabrani folder" else "Pojedinačni fajlovi"
        sheet.addView(text(c, title, 17f, C_T1, bold = true))
        sheet.addView(text(c, "Gde da ih smestim? Fajlovi ostaju na disku gde jesu.", 12.5f, C_T2).apply {
            setPadding(0, c.dp(8), 0, c.dp(16))
        })
        val row = horizontal(c)
        listOf(Section.BOOKS, Section.SERIES, Section.MUSIC, Section.STANDUP).forEach { sec ->
            row.addView(sectionIconCell(c, sec) { applyFilesSection(sec) })
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
            row.addView(sectionIconCell(c, sec) { applyMove(sec) })
        }
        sheet.addView(row)
        sheet.addView(actionButton(c, "Premesti i na disku…", false) {
            toast("Na Androidu 11+ fajlove premesti kroz „Moji fajlovi“")
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, c.dp(42)).apply { topMargin = c.dp(10) } })
        sheet.addView(text(c, "Premeštanje menja samo pripadnost sekciji. Pozicija i status ostaju sačuvani.",
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

    /* ------------------------------------------------------------ noćni ekran i skica zaključanog ekrana */
    private fun nightOverlay(): View {
        val c = this
        val p = Bus.player
        val t = Bus.timer
        val root = vertical(c).apply { setBackgroundColor(Color.rgb(0x05, 0x04, 0x03)) }
        val top = horizontal(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(c.dp(20), c.dp(10), c.dp(20), c.dp(10))
            addView(text(c, "${p?.groupTitle ?: "—"} · ${p?.current?.title ?: ""}", 12f, C_NIGHT),
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
                addView(icon(c, res, C_NIGHT, iconSize))
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

    private fun lockOverlay(): View {
        val c = this
        val p = Bus.player
        val root = FrameLayout(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(0x1D, 0x25, 0x37), Color.rgb(0x0A, 0x0C, 0x12), Color.rgb(0x05, 0x06, 0x08)))
        }
        val body = vertical(c)
        body.addView(text(c, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()),
            64f, Color.rgb(0xD5, 0xD9, 0xE2)).apply {
            gravity = Gravity.CENTER; setPadding(0, c.dp(54), 0, 0)
        })
        body.addView(text(c, SimpleDateFormat("EEEE, d. MMMM", Locale("sr")).format(Date()),
            13f, Color.rgb(0x9A, 0xA1, 0xB0)).apply {
            gravity = Gravity.CENTER; setPadding(0, c.dp(8), 0, 0)
        })
        val card = vertical(c).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(c.dp(12), c.dp(34), c.dp(12), 0)
            }
            background = rounded(Color.argb(24, 255, 255, 255), 24f, c)
            setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(13))
            val hd = horizontal(c).apply { gravity = Gravity.CENTER_VERTICAL }
            hd.addView(cover(c, p?.cover ?: 0, p?.initial ?: "Š", 40, 9f))
            hd.addView(vertical(c).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = c.dp(11) }
                addView(text(c, "ŠAPTAČ", 10f, Color.rgb(0x9A, 0xA1, 0xB0)))
                addView(text(c, p?.current?.title ?: "—", 14.5f, Color.rgb(0xD5, 0xD9, 0xE2)))
                addView(text(c, p?.groupTitle ?: "", 12f, Color.rgb(0x9A, 0xA1, 0xB0)))
            })
            addView(hd)
            val mp = MiniProgress(c)
            addView(mp, LinearLayout.LayoutParams(-1, c.dp(3)).apply { topMargin = c.dp(15) })
            lockProgressRef = mp
            val ctl = horizontal(c).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(13) }
                gravity = Gravity.CENTER_VERTICAL
                addView(iconBox(c, R.drawable.ic_b30, Color.rgb(0xE7, 0xEA, 0xF1), 30, 6) { bump(-30) })
                addView(iconBox(c, if (p?.playing == true) R.drawable.ic_pause else R.drawable.ic_play,
                    Color.rgb(0xE7, 0xEA, 0xF1), 30, 6) { toggle() },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(iconBox(c, R.drawable.ic_f30, Color.rgb(0xE7, 0xEA, 0xF1), 30, 6) { bump(30) })
                addView(text(c, "+${Store.settings.extend}", 12.5f, Color.rgb(0xE7, 0xEA, 0xF1), bold = true).apply {
                    click { extendTimer() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(ctl)
        }
        body.addView(card)
        body.addView(text(c, "Skica sistemskih kontrola · prave kontrole daje MediaSession",
            11f, Color.rgb(0x6F, 0x76, 0x86)).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setPadding(0, 0, 0, c.dp(16))
        })
        root.addView(body, FrameLayout.LayoutParams(-1, -1))
        root.addView(iconBox(c, R.drawable.ic_x, Color.rgb(0x8B, 0x93, 0xA3), 22, 10) {
            Bus.lockPreview = false; render()
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = c.dp(10); rightMargin = c.dp(10)
        })
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

    private fun helpFab(): View {
        val c = this
        return FrameLayout(c).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0x22, 0x1D, 0x19))
                setStroke(c.dp(1), C_LINE)
            }
            addView(text(c, "?", 16f, C_T2, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            click { Nav.sheet = "help"; render() }
        }
    }
}
