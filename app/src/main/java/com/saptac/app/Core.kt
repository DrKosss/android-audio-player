package com.saptac.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
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

/** Keš slika sa ograničenom memorijom: brzi tabovi bez gomilanja bitmapa. */
private fun bitmapCacheKb(divisor: Int): Int =
    (Runtime.getRuntime().maxMemory() / 1024L / divisor).toInt()
        .coerceIn(4 * 1024, 16 * 1024)

class BitmapCache(maxKb: Int) : LruCache<String, Bitmap>(maxKb) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        (value.byteCount / 1024).coerceAtLeast(1)
    operator fun set(key: String, value: Bitmap) { put(key, value) }
    fun containsKey(key: String): Boolean = snapshot().containsKey(key)
    fun clear() = evictAll()
}

/** LruCache.get je finalan Java metod; operator dodajemo kao ekstenziju. */
operator fun BitmapCache.get(key: String): Bitmap? = this.get(key)
/** Folderi i podfolderi, od korena ka fajlu. */
fun pathSegments(key: String): List<String> =
    key.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }


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
    val track: Int,
    val genre: String = "",
    val path: String = ""
)

/** Prava putanja kad postoji, inače URI iz kog fajl može da se prepozna. */
fun diskPathOf(a: AudioItem): String = when {
    a.path.isNotBlank() -> a.path
    a.uri.startsWith("file://") -> a.uri.removePrefix("file://")
    else -> a.uri
}

data class Group(
    val key: String,
    val title: String,
    val subtitle: String,
    val items: List<AudioItem>,
    val cover: Int,
    val initial: String,
    val coverKey: String = key
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

/**
 * Virtuelni, reverzibilni sloj preko AudioItem-a. Fajl na disku se nikad ne dira.
 * Faza 1: source je uvek "local" (nijedan mrežni poziv).
 */
data class NormalizedMetadata(
    val itemId: String,
    val rawTitle: String,
    val rawFolder: String,
    val seriesTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val special: String? = null,
    val displayTitle: String,
    val sortKey: String? = null,
    val seasonGroupKey: String? = null,
    val seasonGroupTitle: String? = null,
    val proposedSection: Section? = null,
    val source: String = "local",
    val confidence: Int = 0,
    val reason: String = "",
    val normalizedAt: Long = 0L,
    val artworkUrl: String? = null,
    val onlineSource: String? = null,
    val onlineConfidence: Int = 0,
    val onlineReason: String? = null,
    val onlineCheckedAt: Long = 0L
)

fun normalizedToJson(m: NormalizedMetadata): JSONObject = JSONObject().apply {
    put("itemId", m.itemId); put("rawTitle", m.rawTitle); put("rawFolder", m.rawFolder)
    put("seriesTitle", m.seriesTitle ?: JSONObject.NULL)
    put("season", m.season ?: JSONObject.NULL)
    put("episode", m.episode ?: JSONObject.NULL)
    put("episodeTitle", m.episodeTitle ?: JSONObject.NULL)
    put("special", m.special ?: JSONObject.NULL)
    put("displayTitle", m.displayTitle)
    put("sortKey", m.sortKey ?: JSONObject.NULL)
    put("seasonGroupKey", m.seasonGroupKey ?: JSONObject.NULL)
    put("seasonGroupTitle", m.seasonGroupTitle ?: JSONObject.NULL)
    put("proposedSection", m.proposedSection?.let { sectionName(it) } ?: JSONObject.NULL)
    put("source", m.source); put("confidence", m.confidence)
    put("reason", m.reason); put("normalizedAt", m.normalizedAt)
    put("artworkUrl", m.artworkUrl ?: JSONObject.NULL)
    put("onlineSource", m.onlineSource ?: JSONObject.NULL)
    put("onlineConfidence", m.onlineConfidence)
    put("onlineReason", m.onlineReason ?: JSONObject.NULL)
    put("onlineCheckedAt", m.onlineCheckedAt)
}

fun normalizedFromJson(o: JSONObject): NormalizedMetadata = NormalizedMetadata(
    itemId = o.optString("itemId"),
    rawTitle = o.optString("rawTitle"),
    rawFolder = o.optString("rawFolder"),
    seriesTitle = o.optString("seriesTitle").takeIf { it.isNotEmpty() },
    season = o.optInt("season", 0).takeIf { it > 0 },
    episode = o.optInt("episode", 0).takeIf { it > 0 },
    episodeTitle = o.optString("episodeTitle").takeIf { it.isNotEmpty() },
    special = o.optString("special").takeIf { it.isNotEmpty() },
    displayTitle = padSeLabel(o.optString("displayTitle").ifBlank { o.optString("rawTitle") }),
    sortKey = o.optString("sortKey").takeIf { it.isNotEmpty() }?.let { padSeLabel(it) },
    seasonGroupKey = o.optString("seasonGroupKey").takeIf { it.isNotEmpty() }?.let { padSeasonKey(it) },
    seasonGroupTitle = o.optString("seasonGroupTitle").takeIf { it.isNotEmpty() }?.let { padSeasonTitle(it) },
    proposedSection = o.optString("proposedSection").takeIf { it.isNotEmpty() }
        ?.let { sectionOf(it).let { s -> if (s == Section.NONE) null else s } },
    source = o.optString("source", "local").ifBlank { "local" },
    confidence = o.optInt("confidence", 0),
    reason = o.optString("reason"),
    normalizedAt = o.optLong("normalizedAt", 0L),
    artworkUrl = o.optString("artworkUrl").takeIf { it.isNotEmpty() },
    onlineSource = o.optString("onlineSource").takeIf { it.isNotEmpty() },
    onlineConfidence = o.optInt("onlineConfidence", 0),
    onlineReason = o.optString("onlineReason").takeIf { it.isNotEmpty() },
    onlineCheckedAt = o.optLong("onlineCheckedAt", 0L)
)

/** Sintetički ključ sezone → osnovni folder, da ručni omot i dalje važi za sve sezone. */
fun coverKeyOf(key: String): String = key.substringBefore("|season|")

/* Jednocifrene sezone/epizode dobijaju vodeću nulu: S1E2 → S01E02, Sezona 1 → Sezona 01. */
private val SE_LABEL_RX = Regex("""S(\d{1,2})E(\d{1,2})""")
private val SE_BARE_RX = Regex("""S(\d{1,2})(?!\d)""")
private val E_BARE_RX = Regex("""E(\d{1,2})(?!\d)""")
private val SEASON_KEY_RX = Regex("""\|season\|S(\d{1,2})""")
private val SEASON_TITLE_RX = Regex("""^Sezona (\d{1,2})$""")

/** Dopunjuje nule u S/E oznakama, uključujući specijale (S2 · …) i gole E2. */
fun padSeLabel(value: String): String {
    val se = SE_LABEL_RX.replace(value) { m ->
        String.format(Locale.US, "S%02dE%02d", m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }
    val s = SE_BARE_RX.replace(se) { m ->
        "S" + String.format(Locale.US, "%02d", m.groupValues[1].toInt())
    }
    return E_BARE_RX.replace(s) { m ->
        "E" + String.format(Locale.US, "%02d", m.groupValues[1].toInt())
    }
}
fun padSeasonKey(value: String): String = SEASON_KEY_RX.replace(value) { m ->
    "|season|S" + String.format(Locale.US, "%02d", m.groupValues[1].toInt())
}
fun padSeasonTitle(value: String): String = SEASON_TITLE_RX.find(value)?.let { m ->
    "Sezona " + String.format(Locale.US, "%02d", m.groupValues[1].toInt())
} ?: value

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
    var repeat: Boolean = false,
    var normalizeOnline: Boolean = true,
    var podcastIndexKey: String = "",
    var podcastIndexSecret: String = ""
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

/* ------------------------------------------------------------------ *
 * Serijalizacija stanja plejera (da poslednji klip preživi dane)
 * ------------------------------------------------------------------ */
fun audioItemToJson(a: AudioItem): JSONObject = JSONObject().apply {
    put("id", a.id); put("uri", a.uri); put("title", a.title)
    put("artist", a.artist); put("album", a.album)
    put("durationMs", a.durationMs); put("folderKey", a.folderKey)
    put("folderName", a.folderName); put("size", a.size)
    put("added", a.added); put("track", a.track); put("genre", a.genre)
    put("path", a.path)
}

fun audioItemFromJson(o: JSONObject): AudioItem = AudioItem(
    id = o.optString("id"), uri = o.optString("uri"),
    title = o.optString("title"), artist = o.optString("artist"),
    album = o.optString("album"), durationMs = o.optLong("durationMs"),
    folderKey = o.optString("folderKey"), folderName = o.optString("folderName"),
    size = o.optLong("size"), added = o.optLong("added"), track = o.optInt("track"),
    genre = o.optString("genre", ""), path = o.optString("path", "")
)

fun playerStateFromJson(o: JSONObject): PlayerState? {
    val q = o.optJSONArray("queue") ?: return null
    val queue = ArrayList<AudioItem>()
    for (i in 0 until q.length()) q.optJSONObject(i)?.let { queue.add(audioItemFromJson(it)) }
    if (queue.isEmpty()) return null
    val key = o.optString("groupKey")
    if (key.isBlank()) return null
    val kind = try { Kind.valueOf(o.optString("kind")) } catch (_: Exception) { Kind.BOOK }
    val idx = o.optInt("index", 0).coerceIn(0, queue.lastIndex)
    val groupTitle = o.optString("groupTitle").ifBlank { key.substringAfterLast('/') }
    return PlayerState(
        kind = kind, groupKey = key, groupTitle = groupTitle,
        sub = o.optString("sub"), queue = queue, index = idx,
        cover = o.optInt("cover", coverIndex(key)),
        initial = o.optString("initial").ifBlank { groupTitle.take(1).uppercase(Locale.ROOT) },
        durationMs = o.optLong("durationMs", queue[idx].durationMs).coerceAtLeast(1L),
        positionMs = o.optLong("pos", 0L).coerceAtLeast(0L),
        playing = false,
        rate = o.optDouble("rate", 1.0).toFloat(),
        shuffle = o.optBoolean("shuffle", false), repeat = o.optBoolean("repeat", false)
    )
}

fun playRequestFromState(p: PlayerState, startMs: Long = p.positionMs): PlayRequest =
    PlayRequest(p.kind, p.groupKey, p.groupTitle, p.sub, p.queue, p.index,
        startMs, p.rate, p.shuffle, p.repeat)

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
    private val cacheLock = Any()
    private val jsonCache = HashMap<String, Any>()
    @Volatile private var dataEpoch = 0L

    /** Menja se posle svakog upisa; keši izvedenih podataka se tada odbacuju. */
    fun epoch(): Long = dataEpoch
    private fun touchEpoch() { synchronized(cacheLock) { dataEpoch++ } }

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("saptac", Context.MODE_PRIVATE)
        synchronized(cacheLock) { jsonCache.clear(); dataEpoch++ }
    }

    private fun str(key: String): String = prefs.getString(key, "{}") ?: "{}"
    private fun arrStr(key: String): String = prefs.getString(key, "[]") ?: "[]"

    /** Parsira SharedPreferences JSON samo jednom po ključu; čitanje je samo za čitanje. */
    private fun cachedObject(key: String): JSONObject = synchronized(cacheLock) {
        when (val hit = jsonCache[key]) {
            is JSONObject -> hit
            else -> {
                val o = try { JSONObject(str(key)) } catch (_: Exception) { JSONObject() }
                jsonCache[key] = o
                o
            }
        }
    }
    private fun cachedArray(key: String): JSONArray = synchronized(cacheLock) {
        when (val hit = jsonCache[key]) {
            is JSONArray -> hit
            else -> {
                val a = try { JSONArray(arrStr(key)) } catch (_: Exception) { JSONArray() }
                jsonCache[key] = a
                a
            }
        }
    }
    private fun obj(key: String): JSONObject = cachedObject(key)
    private fun editObj(key: String): JSONObject = synchronized(cacheLock) {
        JSONObject(cachedObject(key).toString())
    }
    private fun editArray(key: String): JSONArray = synchronized(cacheLock) {
        JSONArray(cachedArray(key).toString())
    }

    private fun put(key: String, value: JSONObject) = put(key, value.toString())
    private fun put(key: String, value: String) {
        val stable: Any = if (value.trimStart().startsWith("[")) {
            try { JSONArray(value) } catch (_: Exception) { JSONArray() }
        } else {
            try { JSONObject(value) } catch (_: Exception) { JSONObject() }
        }
        synchronized(cacheLock) { jsonCache[key] = stable; dataEpoch++ }
        prefs.edit().putString(key, value).apply()
    }

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
                shuffle = o.optBoolean("shuffle", false), repeat = o.optBoolean("repeat", false),
                normalizeOnline = o.optBoolean("normalizeOnline", true),
                podcastIndexKey = o.optString("podcastIndexKey", ""),
                podcastIndexSecret = o.optString("podcastIndexSecret", "")
            )
        }
        set(v) {
            val o = JSONObject()
            o.put("jump", v.jump); o.put("fade", v.fade); o.put("extend", v.extend)
            o.put("gShake", v.gShake); o.put("gVol", v.gVol); o.put("gScreen", v.gScreen)
            o.put("nextAuto", v.nextAuto); o.put("covers", v.covers)
            o.put("autodl", v.autodl); o.put("wifiOnly", v.wifiOnly)
            o.put("timeSpeed", v.timeSpeed); o.put("shuffle", v.shuffle); o.put("repeat", v.repeat)
            o.put("normalizeOnline", v.normalizeOnline)
            o.put("podcastIndexKey", v.podcastIndexKey)
            o.put("podcastIndexSecret", v.podcastIndexSecret)
            put("settings", o)
        }

    fun position(id: String): Long = obj("positions").optLong(id, 0L)
    fun setPosition(id: String, ms: Long) {
        val o = editObj("positions"); o.put(id, ms); put("positions", o)
    }
    fun positions(): JSONObject = editObj("positions")

    fun isDone(id: String): Boolean = obj("done").optBoolean(id, false)
    fun setDone(id: String, value: Boolean) {
        val o = editObj("done"); o.put(id, value); put("done", o)
    }

    fun folderSection(key: String): Section? = sectionOf(obj("folders").optString(key, "").takeIf { it.isNotEmpty() }).let {
        if (it == Section.NONE) null else it
    }
    /** Najbliži roditeljski folder sa ručno dodeljenom sekcijom pobeđuje. */
    fun folderSectionForPath(key: String): Section? {
        var path = key.trimEnd('/', '\\')
        while (path.isNotEmpty()) {
            folderSection(path)?.let { return it }
            val cut = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
            if (cut <= 0) break
            path = path.substring(0, cut).trimEnd('/', '\\')
        }
        return null
    }
    fun setFolderSection(key: String, sec: Section?) {
        val o = editObj("folders")
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
        val o = editObj("overrides")
        if (sec == null || sec == Section.NONE) o.remove(id) else o.put(id, sectionName(sec))
        put("overrides", o)
    }

    /* ---------------- virtuelno sređivanje biblioteke (Faza 1) ---------------- */
    fun normalization(id: String): NormalizedMetadata? =
        obj("normalizations").optJSONObject(id)?.let { normalizedFromJson(it) }
    fun allNormalizations(): Map<String, NormalizedMetadata> {
        val o = obj("normalizations"); val out = LinkedHashMap<String, NormalizedMetadata>()
        o.keys().forEach { k -> o.optJSONObject(k)?.let { out[k] = normalizedFromJson(it) } }
        return out
    }
    fun setNormalization(m: NormalizedMetadata) {
        val o = editObj("normalizations"); o.put(m.itemId, normalizedToJson(m)); put("normalizations", o)
    }
    fun removeNormalization(id: String) {
        val o = editObj("normalizations"); o.remove(id); put("normalizations", o)
    }
    fun saveNormalizations(map: Map<String, NormalizedMetadata>) {
        val o = JSONObject(); map.forEach { (k, v) -> o.put(k, normalizedToJson(v)) }
        put("normalizations", o)
    }

    fun proposal(id: String): NormalizedMetadata? =
        obj("normalizationProposals").optJSONObject(id)?.let { normalizedFromJson(it) }
    fun allProposals(): Map<String, NormalizedMetadata> {
        val o = obj("normalizationProposals"); val out = LinkedHashMap<String, NormalizedMetadata>()
        o.keys().forEach { k -> o.optJSONObject(k)?.let { out[k] = normalizedFromJson(it) } }
        return out
    }
    fun setProposal(m: NormalizedMetadata) {
        val o = editObj("normalizationProposals"); o.put(m.itemId, normalizedToJson(m)); put("normalizationProposals", o)
    }
    fun removeProposal(id: String) {
        val o = editObj("normalizationProposals"); o.remove(id); put("normalizationProposals", o)
    }
    fun saveProposals(map: Map<String, NormalizedMetadata>) {
        val o = JSONObject(); map.forEach { (k, v) -> o.put(k, normalizedToJson(v)) }
        put("normalizationProposals", o)
    }

    fun isDismissed(id: String): Boolean = obj("normalizationDismissed").optBoolean(id, false)
    fun dismiss(id: String) {
        val o = editObj("normalizationDismissed"); o.put(id, true); put("normalizationDismissed", o)
    }
    fun undismiss(id: String) {
        val o = editObj("normalizationDismissed"); o.remove(id); put("normalizationDismissed", o)
    }
    fun allDismissed(): JSONObject = editObj("normalizationDismissed")

    /** Ručna, isključivo virtuelna imena fajlova i grupa. */
    fun manualTitle(key: String): String? =
        obj("manualTitles").optString(key, "").takeIf { it.isNotEmpty() }
    fun setManualTitle(key: String, title: String) {
        val o = editObj("manualTitles"); o.put(key, title); put("manualTitles", o)
    }
    fun clearManualTitle(key: String) {
        val o = editObj("manualTitles"); o.remove(key); put("manualTitles", o)
    }
    fun allManualTitles(): Map<String, String> {
        val o = obj("manualTitles"); val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k, "") }
        return out
    }

    /** Snapshot pre svakog automatskog pokretanja, za "Vrati sve". */
    fun saveNormalizationSnapshot() {
        val snap = JSONObject()
        snap.put("normalizations", obj("normalizations"))
        snap.put("proposals", obj("normalizationProposals"))
        snap.put("dismissed", obj("normalizationDismissed"))
        snap.put("manualTitles", obj("manualTitles"))
        put("normalizationSnapshot", snap)
    }
    fun restoreNormalizationSnapshot(): Boolean {
        val snap = obj("normalizationSnapshot")
        if (snap.length() == 0) return false
        put("normalizations", snap.optJSONObject("normalizations") ?: JSONObject())
        put("normalizationProposals", snap.optJSONObject("proposals") ?: JSONObject())
        put("normalizationDismissed", snap.optJSONObject("dismissed") ?: JSONObject())
        put("manualTitles", snap.optJSONObject("manualTitles") ?: JSONObject())
        return true
    }
    fun normalizationSnapshot(): JSONObject = editObj("normalizationSnapshot")

    /** Rotira kroz sve jedinstvene internet upite kroz više pokretanja (Faza 2). */
    var onlineCursor: Int
        get() = prefs.getInt("normalizationOnlineCursor", 0)
        set(v) {
            prefs.edit().putInt("normalizationOnlineCursor", v).apply()
            touchEpoch()
        }

    /** Keš jednog malog omota po seriji/albumu, sa ETag i vremenom preuzimanja. */
    fun artCacheEntry(key: String): JSONObject? = obj("normalizationArtCache").optJSONObject(key)
    fun artCache(): JSONObject = editObj("normalizationArtCache")
    fun setArtCacheEntry(key: String, o: JSONObject) {
        val all = editObj("normalizationArtCache"); all.put(key, o); put("normalizationArtCache", all)
    }
    fun clearArtCacheEntry(key: String) {
        val all = editObj("normalizationArtCache"); all.remove(key); put("normalizationArtCache", all)
    }

    fun lastGroupTime(key: String): Long = obj("recent").optLong(key, 0L)
    fun touchGroup(key: String) {
        val o = editObj("recent"); o.put(key, System.currentTimeMillis()); put("recent", o)
    }

    var importedOnce: Boolean
        get() = prefs.getBoolean("importedOnce", false)
        set(v) {
            prefs.edit().putBoolean("importedOnce", v).apply()
            touchEpoch()
        }

    fun feedCount(): Int = cachedArray("feeds").length()

    fun feeds(): MutableList<Feed> {
        val a = cachedArray("feeds")
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
        val a = cachedArray("trees")
        val out = ArrayList<String>()
        for (i in 0 until a.length()) a.optString(i)?.let { out.add(it) }
        return out
    }
    fun addTreeUri(uri: String) {
        val list = treeUris()
        if (!list.contains(uri)) { list.add(uri); put("trees", JSONArray(list).toString()) }
    }

    fun log(message: String) {
        val a = editArray("log")
        val stamp = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", Locale.getDefault()).format(java.util.Date())
        a.put("$stamp  $message")
        while (a.length() > 250) a.remove(0)
        put("log", a.toString())
    }
    fun logs(): List<String> {
        val a = cachedArray("log")
        val out = ArrayList<String>()
        for (i in a.length() - 1 downTo 0) a.optString(i)?.let { out.add(it) }
        return out
    }
    fun clearLog() = put("log", "[]")

    /** Sirovi JSON za izvoz / dijagnostiku. */
    fun rawObject(key: String): JSONObject = editObj(key)
    fun rawArray(key: String): JSONArray = editArray(key)

    fun lastPlayer(): JSONObject = editObj("lastPlayer")
    fun saveLastPlayer(o: JSONObject) = put("lastPlayer", o)

    /** Ručno izabran omot: SAF URI slike po grupi (knjizi/seriji/albumu). */
    fun customCover(key: String): String? =
        obj("covers").optString(key, "").takeIf { it.isNotEmpty() }
    fun setCustomCover(key: String, uri: String) {
        val o = editObj("covers"); o.put(key, uri); put("covers", o)
    }
    fun clearCustomCover(key: String) {
        val o = editObj("covers"); o.remove(key); put("covers", o)
    }

    fun rateForGroup(key: String): Float = obj("rates").optDouble(key, 1.0).toFloat()
    fun setRateForGroup(key: String, rate: Float) {
        val o = editObj("rates"); o.put(key, rate.toDouble()); put("rates", o)
    }

    /** Pojedinačni fajlovi uvezeni kroz sistemski izbor fajlova. */
    fun loose(): Map<String, Section> {
        val o = obj("loose"); val out = LinkedHashMap<String, Section>()
        o.keys().forEach { k -> out[k] = sectionOf(o.optString(k)) }
        return out
    }
    fun setLoose(uri: String, sec: Section) {
        val o = editObj("loose"); o.put(uri, sectionName(sec)); put("loose", o)
    }
}

/* ------------------------------------------------------------------ *
 * Zajedničko stanje u procesu (UI i servis su u istom procesu)
 * ------------------------------------------------------------------ */
/** Stanje ručno pokrenute internet pretrage cele biblioteke. */
data class OnlineScanState(
    val done: Int,
    val total: Int,
    val label: String,
    val running: Boolean,
    val message: String? = null
)

object Bus {
    var library: Library = Library(emptyList(), emptyList())
    var player: PlayerState? = null
    var timer: TimerState? = null
    var night: Boolean = false
    var toast: String? = null
    var toastUntil: Long = 0L
    var playRequest: PlayRequest? = null
    var libraryVersion: Int = 0
    var normalizationVersion: Int = 0
    var seeking: Boolean = false
    var undo: Pair<Long, Long>? = null   // stara pozicija, vreme
    val covers = BitmapCache(bitmapCacheKb(16))
    val onlineCovers = BitmapCache(bitmapCacheKb(24))
    val onlineCoverMisses: MutableSet<String> =
        java.util.Collections.synchronizedSet(java.util.HashSet<String>())
    @Volatile var onlineScan: OnlineScanState? = null
    @Volatile var onlineScanCancel: Boolean = false

    fun showToast(message: String, millis: Long = 2200) {
        toast = message; toastUntil = System.currentTimeMillis() + millis
    }
    fun log(message: String) = Store.log(message)
}
