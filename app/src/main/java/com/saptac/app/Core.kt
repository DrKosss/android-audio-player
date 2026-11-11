package com.saptac.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/* ------------------------------------------------------------------ *
 * Paleta — iste vrednosti kao u prototipu (saptac-prototip.html)
 * ------------------------------------------------------------------ */
val C_BG = Color.rgb(0x00, 0x00, 0x00)
val C_S1 = Color.rgb(0x13, 0x11, 0x10)
val C_S2 = Color.rgb(0x1D, 0x1A, 0x17)
val C_S3 = Color.rgb(0x24, 0x1F, 0x1B)
val C_LINE = Color.rgb(0x2A, 0x25, 0x21)
val C_T1 = Color.rgb(0xEC, 0xE5, 0xD9)
val C_T2 = Color.rgb(0x9B, 0x92, 0x86)
val C_T3 = Color.rgb(0x6E, 0x66, 0x5C)
val C_AMBER = Color.rgb(0xEF, 0xA1, 0x3A)
val C_AMBER_D = Color.rgb(0x2C, 0x20, 0x13)
val C_NIGHT = Color.rgb(0x8A, 0x56, 0x20)
val C_INK = Color.rgb(0x23, 0x15, 0x03)
val C_PAPER = Color.rgb(0xF4, 0xF1, 0xEA)

/** Šest gradijentnih omota iz prototipa. */
val COVERS = arrayOf(
    intArrayOf(Color.rgb(0x6A, 0x43, 0x20), Color.rgb(0x2E, 0x1E, 0x10)),
    intArrayOf(Color.rgb(0x2B, 0x4A, 0x45), Color.rgb(0x16, 0x27, 0x24)),
    intArrayOf(Color.rgb(0x46, 0x3A, 0x66), Color.rgb(0x23, 0x1D, 0x36)),
    intArrayOf(Color.rgb(0x5C, 0x2C, 0x35), Color.rgb(0x2C, 0x15, 0x1A)),
    intArrayOf(Color.rgb(0x3C, 0x4A, 0x2A), Color.rgb(0x1D, 0x24, 0x14)),
    intArrayOf(Color.rgb(0x30, 0x45, 0x5C), Color.rgb(0x17, 0x21, 0x2D))
)

fun coverIndex(key: String): Int {
    var h = 0
    for (ch in key) h = h * 31 + ch.code
    return (h and 0x7fffffff) % COVERS.size
}

/* ------------------------------------------------------------------ *
 * Model
 * ------------------------------------------------------------------ */
enum class Section { BOOKS, SERIES, MUSIC, STANDUP, NONE }
enum class Kind { BOOK, EPISODE, TRACK, STANDUP }
enum class TPhase { RUN, FADE, GRACE }

fun sectionName(s: Section?): String = when (s) {
    Section.BOOKS -> "Knjige"
    Section.SERIES -> "Emisije"
    Section.MUSIC -> "Muzika"
    Section.STANDUP -> "Stand up"
    else -> "?"
}

/** Ikonica sekcije; za neraspoređene foldere ostaje generički folder. */
fun sectionIcon(s: Section?): Int = when (s) {
    Section.BOOKS -> R.drawable.ic_book
    Section.SERIES -> R.drawable.ic_radio
    Section.MUSIC -> R.drawable.ic_music
    Section.STANDUP -> R.drawable.ic_standup
    else -> R.drawable.ic_folder
}

data class AudioItem(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val folderKey: String,
    val folderName: String,
    val size: Long,
    val added: Long,
    val track: Int
)

data class Group(
    val key: String,
    val title: String,
    val subtitle: String,
    val items: List<AudioItem>,
    val cover: Int,
    val initial: String
) {
    val durationMs: Long get() = items.sumOf { it.durationMs.coerceAtLeast(0) }
}

data class FolderInfo(
    val key: String,
    val name: String,
    val count: Int,
    val suggested: Section?,
    val assigned: Section?
)

data class Library(
    val items: List<AudioItem>,
    val folders: List<FolderInfo>
)

data class Settings(
    var jump: Int = 30,
    var fade: Int = 60,
    var extend: Int = 15,
    var gShake: Boolean = true,
    var gVol: Boolean = true,
    var gScreen: Boolean = true,
    var nextAuto: Boolean = true,
    var covers: Boolean = true,
    var autodl: Boolean = true,
    var wifiOnly: Boolean = true,
    var timeSpeed: Int = 1,
    var shuffle: Boolean = false,
    var repeat: Boolean = false
)

data class TimerState(
    val totalMs: Long,
    val leftMs: Long,
    val fadeMs: Long,
    val extendMin: Int,
    val phase: TPhase,
    val graceMs: Long
)

data class PlayerState(
    val kind: Kind,
    val groupKey: String,
    val groupTitle: String,
    val sub: String,
    val queue: List<AudioItem>,
    val index: Int,
    val cover: Int,
    val initial: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    val rate: Float,
    val shuffle: Boolean,
    val repeat: Boolean
) {
    val current: AudioItem get() = queue[index]
    val sectionLabel: String get() = when (kind) {
        Kind.BOOK -> "KNJIGE"
        Kind.EPISODE -> "EMISIJE"
        Kind.TRACK -> "MUZIKA"
        Kind.STANDUP -> "STAND UP"
    }
}

data class PlayRequest(
    val kind: Kind,
    val groupKey: String,
    val groupTitle: String,
    val sub: String,
    val queue: List<AudioItem>,
    val index: Int,
    val startMs: Long,
    val rate: Float,
    val shuffle: Boolean = false,
    val repeat: Boolean = false
)

data class FeedEpisode(
    val title: String,
    val url: String,
    val durationMs: Long,
    val date: String,
    val local: String = ""
)

data class Feed(
    val id: String,
    val title: String,
    val url: String,
    val author: String = "",
    val unread: Int = 0,
    val lastRefresh: Long = 0L,
    val episodes: List<FeedEpisode> = emptyList()
)

data class CatalogItem(
    val title: String,
    val author: String,
    val genre: String,
    val hint: String,
    val feedUrl: String,
    val art: String = ""
)

/* ------------------------------------------------------------------ *
 * Formatiranje
 * ------------------------------------------------------------------ */
fun fmtTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = (s % 3600) / 60
    val x = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, x)
    else String.format(Locale.US, "%02d:%02d", m, x)
}

fun fmtShort(ms: Long): String {
    val totalMin = ((ms.coerceAtLeast(0) / 1000) / 60).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h h ${String.format(Locale.US, "%02d", m)} min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

fun fmtRate(r: Float): String =
    String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.').replace('.', ',') + "×"

fun sectionOf(name: String?): Section = when (name) {
    "Knjige" -> Section.BOOKS
    "Emisije" -> Section.SERIES
    "Muzika" -> Section.MUSIC
    "Stand up", "stand up" -> Section.STANDUP
    else -> Section.NONE
}

/* ------------------------------------------------------------------ *
 * Lokalna baza (SharedPreferences + JSON, bez spoljnih biblioteka)
 * ------------------------------------------------------------------ */
object Store {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("saptac", Context.MODE_PRIVATE)
    }

    private fun str(key: String): String = prefs.getString(key, "{}") ?: "{}"
    private fun obj(key: String): JSONObject = try { JSONObject(str(key)) } catch (_: Exception) { JSONObject() }
    private fun put(key: String, value: JSONObject) = prefs.edit().putString(key, value.toString()).apply()
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    var settings: Settings
        get() {
            val o = obj("settings")
            return Settings(
                jump = o.optInt("jump", 30), fade = o.optInt("fade", 60),
                extend = o.optInt("extend", 15),
                gShake = o.optBoolean("gShake", true), gVol = o.optBoolean("gVol", true),
                gScreen = o.optBoolean("gScreen", true), nextAuto = o.optBoolean("nextAuto", true),
                covers = o.optBoolean("covers", true), autodl = o.optBoolean("autodl", true),
                wifiOnly = o.optBoolean("wifiOnly", true), timeSpeed = o.optInt("timeSpeed", 1),
                shuffle = o.optBoolean("shuffle", false), repeat = o.optBoolean("repeat", false)
            )
        }
        set(v) {
            val o = JSONObject()
            o.put("jump", v.jump); o.put("fade", v.fade); o.put("extend", v.extend)
            o.put("gShake", v.gShake); o.put("gVol", v.gVol); o.put("gScreen", v.gScreen)
            o.put("nextAuto", v.nextAuto); o.put("covers", v.covers)
            o.put("autodl", v.autodl); o.put("wifiOnly", v.wifiOnly)
            o.put("timeSpeed", v.timeSpeed); o.put("shuffle", v.shuffle); o.put("repeat", v.repeat)
            put("settings", o)
        }

    fun position(id: String): Long = obj("positions").optLong(id, 0L)
    fun setPosition(id: String, ms: Long) {
        val o = obj("positions"); o.put(id, ms); put("positions", o)
    }
    fun positions(): JSONObject = obj("positions")

    fun isDone(id: String): Boolean = obj("done").optBoolean(id, false)
    fun setDone(id: String, value: Boolean) {
        val o = obj("done"); o.put(id, value); put("done", o)
    }

    fun folderSection(key: String): Section? = sectionOf(obj("folders").optString(key, "").takeIf { it.isNotEmpty() }).let {
        if (it == Section.NONE) null else it
    }
    fun setFolderSection(key: String, sec: Section?) {
        val o = obj("folders")
        if (sec == null || sec == Section.NONE) o.remove(key) else o.put(key, sectionName(sec))
        put("folders", o)
    }
    fun allFolderSections(): Map<String, Section> {
        val o = obj("folders"); val out = LinkedHashMap<String, Section>()
        o.keys().forEach { k -> val s = sectionOf(o.optString(k)); if (s != Section.NONE) out[k] = s }
        return out
    }
    fun hasAnyFolder(): Boolean = obj("folders").length() > 0

    fun override(id: String): Section? = sectionOf(obj("overrides").optString(id, "").takeIf { it.isNotEmpty() }).let {
        if (it == Section.NONE) null else it
    }
    fun setOverride(id: String, sec: Section?) {
        val o = obj("overrides")
        if (sec == null || sec == Section.NONE) o.remove(id) else o.put(id, sectionName(sec))
        put("overrides", o)
    }

    fun lastGroupTime(key: String): Long = obj("recent").optLong(key, 0L)
    fun touchGroup(key: String) {
        val o = obj("recent"); o.put(key, System.currentTimeMillis()); put("recent", o)
    }

    var importedOnce: Boolean
        get() = prefs.getBoolean("importedOnce", false)
        set(v) = prefs.edit().putBoolean("importedOnce", v).apply()

    fun feeds(): MutableList<Feed> {
        val a = try { JSONArray(str("feeds")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<Feed>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val eps = ArrayList<FeedEpisode>()
            val ea = o.optJSONArray("episodes") ?: JSONArray()
            for (j in 0 until ea.length()) {
                val e = ea.optJSONObject(j) ?: continue
                eps.add(FeedEpisode(e.optString("title"), e.optString("url"),
                    e.optLong("durationMs"), e.optString("date"), e.optString("local")))
            }
            out.add(Feed(o.optString("id"), o.optString("title"), o.optString("url"),
                o.optString("author"), o.optInt("unread"), o.optLong("lastRefresh"), eps))
        }
        return out
    }
    fun saveFeeds(list: List<Feed>) {
        val a = JSONArray()
        list.forEach { f ->
            val o = JSONObject()
            o.put("id", f.id); o.put("title", f.title); o.put("url", f.url)
            o.put("author", f.author); o.put("unread", f.unread); o.put("lastRefresh", f.lastRefresh)
            val ea = JSONArray()
            f.episodes.forEach { e ->
                val eo = JSONObject()
                eo.put("title", e.title); eo.put("url", e.url)
                eo.put("durationMs", e.durationMs); eo.put("date", e.date); eo.put("local", e.local)
                ea.put(eo)
            }
            o.put("episodes", ea); a.put(o)
        }
        put("feeds", a.toString())
    }

    fun treeUris(): MutableList<String> {
        val a = try { JSONArray(str("trees")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<String>()
        for (i in 0 until a.length()) a.optString(i)?.let { out.add(it) }
        return out
    }
    fun addTreeUri(uri: String) {
        val list = treeUris()
        if (!list.contains(uri)) { list.add(uri); put("trees", JSONArray(list).toString()) }
    }

    fun log(message: String) {
        val a = try { JSONArray(str("log")) } catch (_: Exception) { JSONArray() }
        val stamp = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        a.put("$stamp  $message")
        while (a.length() > 250) a.remove(0)
        put("log", a.toString())
    }
    fun logs(): List<String> {
        val a = try { JSONArray(str("log")) } catch (_: Exception) { JSONArray() }
        val out = ArrayList<String>()
        for (i in a.length() - 1 downTo 0) a.optString(i)?.let { out.add(it) }
        return out
    }
    fun clearLog() = put("log", "[]")

    fun lastPlayer(): JSONObject = obj("lastPlayer")
    fun saveLastPlayer(o: JSONObject) = put("lastPlayer", o)

    /** Ručno izabran omot: SAF URI slike po grupi (knjizi/seriji/albumu). */
    fun customCover(key: String): String? =
        obj("covers").optString(key, "").takeIf { it.isNotEmpty() }
    fun setCustomCover(key: String, uri: String) {
        val o = obj("covers"); o.put(key, uri); put("covers", o)
    }
    fun clearCustomCover(key: String) {
        val o = obj("covers"); o.remove(key); put("covers", o)
    }

    fun rateForGroup(key: String): Float = obj("rates").optDouble(key, 1.0).toFloat()
    fun setRateForGroup(key: String, rate: Float) {
        val o = obj("rates"); o.put(key, rate.toDouble()); put("rates", o)
    }

    /** Pojedinačni fajlovi uvezeni kroz sistemski izbor fajlova. */
    fun loose(): Map<String, Section> {
        val o = obj("loose"); val out = LinkedHashMap<String, Section>()
        o.keys().forEach { k -> out[k] = sectionOf(o.optString(k)) }
        return out
    }
    fun setLoose(uri: String, sec: Section) {
        val o = obj("loose"); o.put(uri, sectionName(sec)); put("loose", o)
    }
}

/* ------------------------------------------------------------------ *
 * Zajedničko stanje u procesu (UI i servis su u istom procesu)
 * ------------------------------------------------------------------ */
object Bus {
    var library: Library = Library(emptyList(), emptyList())
    var player: PlayerState? = null
    var timer: TimerState? = null
    var night: Boolean = false
    var toast: String? = null
    var toastUntil: Long = 0L
    var playRequest: PlayRequest? = null
    var libraryVersion: Int = 0
    var seeking: Boolean = false
    var undo: Pair<Long, Long>? = null   // stara pozicija, vreme
    var covers: HashMap<String, android.graphics.Bitmap> = HashMap()

    fun showToast(message: String, millis: Long = 2200) {
        toast = message; toastUntil = System.currentTimeMillis() + millis
    }
    fun log(message: String) = Store.log(message)
}
