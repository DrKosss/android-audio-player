package com.saptac.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "ogg", "opus", "flac", "wav", "aac", "aiff", "aif", "mpga")
private val SKIP_DIR = Regex("^(Android|Notifications|Alarms|Ringtones|\\..*|LOST\\.DIR)$", RegexOption.IGNORE_CASE)

private fun isAudioName(name: String): Boolean =
    AUDIO_EXT.contains(name.substringAfterLast('.', "").lowercase(Locale.ROOT))

/** Procena trajanja za fajlove koje MediaStore nije indeksirao (128 kbit/s). */
private fun estimateDuration(sizeBytes: Long): Long =
    if (sizeBytes > 0) (sizeBytes * 8_000L / 128_000L) else 0L

private fun cleanTitle(name: String): String =
    name.substringBeforeLast('.', name).trim().ifEmpty { "Bez naziva" }

/** Žanr iz ID3/MediaStore-a, uz blagi fallback na ime foldera kad taga nema. */
private val GENRE_WORDS = listOf(
    "rock" to "Rock", "pop" to "Pop", "jazz" to "Jazz", "blues" to "Blues",
    "classical" to "Klasična", "klasik" to "Klasična", "metal" to "Metal",
    "hip hop" to "Hip hop", "hiphop" to "Hip hop", "rap" to "Rap",
    "folk" to "Folk", "techno" to "Techno", "house" to "House",
    "electronic" to "Elektronska", "elektron" to "Elektronska",
    "reggae" to "Reggae", "country" to "Country", "dance" to "Dance",
    "soundtrack" to "Soundtrack", "podcast" to "Podkast", "comedy" to "Komedija",
    "audiobook" to "Audio knjiga", "stand up" to "Stand up"
)
private fun guessGenre(vararg parts: String): String {
    val text = parts.joinToString(" ").lowercase(Locale.ROOT)
    GENRE_WORDS.forEach { (needle, label) ->
        if (Regex("\\b" + Regex.escape(needle) + "\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)) return label
    }
    return ""
}

/** Keš grupa važi dok se biblioteka, normalizacija ili lokalna baza ne promene. */
private data class GroupCacheStamp(
    val libraryVersion: Int,
    val normalizationVersion: Int,
    val storeEpoch: Long
)


/** Ručno ime sa S##E## / Sezona NN: virtuelna sezona, bez diranja fajla na disku. */
data class ManualSeason(
    val season: Int,
    val episode: Int?,
    val seriesTitle: String,
    val episodeTitle: String?,
    val sortKey: String
)

private val MANUAL_SE = Regex("""\bS(\d{1,2})\s*E(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val MANUAL_SEASON_WORD = Regex("""\b(?:season|sezona)\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val MANUAL_SEASON_BARE = Regex("""\bS(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val STANDUP_SPLIT = Regex("""\s+[-–—:|]\s+|\s+\.\s+""")

private fun cleanPart(value: String): String = value
    .trim(' ', '-', '–', '—', '·', ':', '.', ',', '|', '(', ')', '[', ']')
    .trim()

/** MediaStore često upiše "<unknown>" ili ime foldera umesto pravog izvođača. */
private fun usefulArtist(item: AudioItem): String {
    val a = item.artist.trim()
    if (a.isBlank() || a.equals("<unknown>", true) || a.equals("unknown", true)) return ""
    if (a.equals(item.folderName, true)) return ""
    return a
}

private val BOOK_EXT = Regex("""\.(mp3|m4a|m4b|ogg|opus|flac|wav|aac|aiff|aif|mpga)$""", RegexOption.IGNORE_CASE)
private val BOOK_TRACK_PREFIX = Regex(
    """^\s*(?:#?\d{1,4}\s*[-–—:.)]\s*|(?:chapter|ch|glava|poglavlj\w*|deo|dio|part|pt|track|disk|cd)\s*\.?\s*\d{1,4}\s*[-–—:.)]?\s*)""",
    RegexOption.IGNORE_CASE)
private val BOOK_CHAPTER_SUFFIX = Regex(
    """\s*[-–—:|(]\s*(?:chapter|ch|glava|poglavlj\w*|deo|dio|part|pt|track|disk|cd)\s*\.?\s*\d{1,4}\s*[)|]?\s*$""",
    RegexOption.IGNORE_CASE)
private val BOOK_COUNTER_SUFFIX = Regex(
    """\s*\((?:\d{1,4}\s*(?:/|of|od)\s*\d{1,4}|\d{1,4})\)\s*$""",
    RegexOption.IGNORE_CASE)
private val BOOK_DASH = Regex("""\s+[-–—]\s+""")
private val BOOK_SPACES = Regex("""\s+""")

/** Skida ekstenziju, brojeve poglavlja i tehničke sufikse, ali čuva pravo ime knjige. */
private fun cleanBookText(raw: String): String {
    var t = raw.trim().replace('_', ' ')
    t = BOOK_EXT.replace(t, "")
    t = BOOK_TRACK_PREFIX.replace(t, "")
    t = BOOK_CHAPTER_SUFFIX.replace(t, "")
    t = BOOK_COUNTER_SUFFIX.replace(t, "")
    t = BOOK_SPACES.replace(t, " ").trim()
    return t.trim(' ', '-', '–', '—', '·', ':', '.', ',', '|', '(', ')', '[', ']')
        .ifBlank { cleanTitle(raw) }
}

/** Obrazac „Autor – Naslov“ kada tag autora nije upisan. */
private fun splitBookAuthor(raw: String): Pair<String, String>? {
    val parts = BOOK_DASH.split(raw, limit = 2)
    if (parts.size != 2) return null
    val author = cleanBookText(parts[0])
    val title = cleanBookText(parts[1])
    if (author.isBlank() || title.isBlank()) return null
    if (author.any { it.isDigit() } || author.split(' ').size > 5 || author.length > 60) return null
    return author to title
}

private fun bookAlbum(item: AudioItem): String =
    item.album.trim().takeIf { it.isNotBlank() && !it.equals(item.folderName, true) } ?: ""

private fun bookFolder(item: AudioItem): String = cleanBookText(item.folderName)

/** Autor knjige: ID3/MediaStore tag, pa obrazac „Autor – Naslov“ iz foldera/albuma/imena fajla. */
fun bookAuthor(item: AudioItem): String {
    usefulArtist(item).takeIf { it.isNotBlank() }?.let { return it }
    listOf(bookFolder(item), bookAlbum(item), item.title).forEach { raw ->
        splitBookAuthor(raw)?.first?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

/** Čisto ime knjige za grupu: album > folder > ime fajla. Ručno ime grupe ima prednost u buildGroups. */
fun bookGroupTitle(item: AudioItem): String {
    val album = bookAlbum(item)
    if (album.isNotBlank()) return splitBookAuthor(album)?.second ?: cleanBookText(album)
    val folder = bookFolder(item)
    return (splitBookAuthor(folder)?.second ?: folder).ifBlank { cleanBookText(item.title) }
}

/** Čisto ime fajla knjige: ručno ime > normalizovano > očišćeno ime fajla. */
fun bookFileTitle(item: AudioItem): String {
    Store.manualTitle("item:" + item.id)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    Store.normalization(item.id)?.displayTitle?.takeIf { it.isNotBlank() }?.let { return cleanBookText(it) }
    return (splitBookAuthor(item.title)?.second ?: cleanBookText(item.title)).ifBlank { "Bez naziva" }
}

/** Izvođač numere, za prikaz ispod pesme u folderima. */
fun musicArtist(item: AudioItem): String = usefulArtist(item)

/** Iz ručnog imena izvlači sezonu, epizodu, ime serije i naslov epizode. */
fun manualSeasonFromTitle(title: String, fallbackSeries: String): ManualSeason? {
    val se = MANUAL_SE.find(title)
    val seasonWord = MANUAL_SEASON_WORD.find(title)
    val bare = MANUAL_SEASON_BARE.find(title)
    val match = se ?: seasonWord ?: bare ?: return null
    val season = match.groupValues[1].toIntOrNull() ?: return null
    val episode = if (se != null) se.groupValues[2].toIntOrNull() else null
    val prefix = cleanPart(title.substring(0, match.range.first))
    val tail = cleanPart(title.substring(match.range.last + 1))
    val sortKey = if (episode != null)
        String.format(Locale.US, "S%02dE%02d", season, episode)
    else String.format(Locale.US, "S%02d", season)
    return ManualSeason(
        season = season,
        episode = episode,
        seriesTitle = prefix.ifBlank { fallbackSeries },
        episodeTitle = tail.takeIf { it.isNotBlank() },
        sortKey = sortKey
    )
}

private fun standupParts(item: AudioItem): List<String> {
    val raw = Store.manualTitle("item:" + item.id) ?: item.title
    val t = raw.substringBeforeLast('.', raw).trim()
    return STANDUP_SPLIT.split(t).map { it.trim() }.filter { it.isNotEmpty() }
}

object LibraryManager {

    /** Jedan izvor istine: MediaStore + (opciono) direktan pristup disku + SAF folderi. */
    fun scan(ctx: Context): Library {
        val byId = LinkedHashMap<String, AudioItem>()

        // 1) MediaStore — radi sa READ_MEDIA_AUDIO bez "pristupa svim fajlovima".
        try {
            val baseProjection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.Audio.Media.TRACK
            )
            val projection = baseProjection + MediaStore.Audio.Media.GENRE
            val cursor = try {
                ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
                )
            } catch (_: Exception) {
                ctx.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, baseProjection, null, null, null
                )
            }
            cursor?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val iDur = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val iName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val iDate = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val iBucket = c.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                val iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val iTrack = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val iGenre = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: "audio"
                    if (!isAudioName(name)) continue
                    val rel = if (iRel >= 0) c.getString(iRel) ?: "" else ""
                    if (rel.contains("Notifications", true) || rel.contains("Alarms", true) ||
                        rel.contains("Ringtones", true)
                    ) continue
                    val dur = if (iDur >= 0) c.getLong(iDur) else 0L
                    if (dur in 1..9_999L) continue
                    val folderKey = rel.trimEnd('/').ifEmpty {
                        if (iBucket >= 0) c.getString(iBucket) ?: "Telefon" else "Telefon"
                    }
                    val folderName = folderKey.substringAfterLast('/').ifEmpty { "Telefon" }
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                    ).toString()
                    val artistVal = (if (iArtist >= 0) c.getString(iArtist) else null)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: ""
                    val albumVal = (if (iAlbum >= 0) c.getString(iAlbum) else null)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: folderName
                    val genreVal = (if (iGenre >= 0) c.getString(iGenre) else null)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: guessGenre(rel, name, artistVal, albumVal)
                    val item = AudioItem(
                        id = "ms:$id", uri = uri,
                        title = (if (iTitle >= 0) c.getString(iTitle) else null)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: cleanTitle(name),
                        artist = artistVal, album = albumVal,
                        durationMs = dur,
                        folderKey = folderKey, folderName = folderName,
                        size = if (iSize >= 0) c.getLong(iSize) else 0L,
                        added = if (iDate >= 0) c.getLong(iDate) * 1000L else 0L,
                        track = if (iTrack >= 0) c.getInt(iTrack) else 0,
                        genre = genreVal
                    )
                    byId[item.uri] = item
                }
            }
        } catch (e: Exception) {
            Bus.log("MediaStore pregled nije uspeo: ${e.javaClass.simpleName}")
        }

        // 2) Kada je data dozvola "Pristup svim fajlovima", čitamo i neindeksirane foldere.
        try {
            if (Environment.isExternalStorageManager()) {
                val roots = LinkedHashSet<File>()
                roots.add(Environment.getExternalStorageDirectory())
                ctx.getExternalFilesDirs(null).forEach { f ->
                    if (f == null) return@forEach
                    val p = f.absolutePath
                    val k = p.indexOf("/Android/data")
                    if (k > 0) roots.add(File(p.substring(0, k)))
                }
                roots.forEach { root -> walk(root, 0, byId) }
            }
        } catch (e: Exception) {
            Bus.log("Direktan pregled diska nije uspeo: ${e.javaClass.simpleName}")
        }

        // 3) Folderi koje je korisnik izabrao kroz sistemski izbor (radi i na SD kartici).
        Store.treeUris().forEach { tree ->
            try {
                val doc = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: return@forEach
                walkDoc(ctx, doc, doc.name ?: "Izabrani folder", 0, byId)
            } catch (e: Exception) {
                Bus.log("SAF folder nije pročitan: ${e.javaClass.simpleName}")
            }
        }

        // 4) Pojedinačni fajlovi koje je korisnik sam izabrao.
        Store.loose().forEach { (uri, sec) ->
            try {
                val doc = DocumentFile.fromSingleUri(ctx, Uri.parse(uri)) ?: return@forEach
                val name = doc.name ?: "Audio"
                val item = AudioItem(
                    id = "loose:$uri", uri = uri, title = cleanTitle(name),
                    artist = "", album = "Pojedinačni fajlovi",
                    durationMs = estimateDuration(doc.length()),
                    folderKey = "Pojedinačni fajlovi", folderName = "Pojedinačni fajlovi",
                    size = doc.length(), added = doc.lastModified(), track = 0,
                    genre = guessGenre(name, "Pojedinačni fajlovi")
                )
                byId[uri] = item
                Store.setOverride(item.id, sec)
            } catch (e: Exception) {
                Bus.log("Uvezeni fajl nije pročitan: ${e.javaClass.simpleName}")
            }
        }

        val items = byId.values.sortedWith(compareBy({ it.folderKey.lowercase(Locale.ROOT) }, { it.track }, { it.title.lowercase(Locale.ROOT) }))
        val folders = buildFolders(items)
        return Library(items, folders)
    }

    private fun walk(dir: File, depth: Int, out: MutableMap<String, AudioItem>) {
        if (depth > 5 || out.size > 6000 || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size > 6000) return
            if (f.isDirectory) {
                if (!SKIP_DIR.matches(f.name)) walk(f, depth + 1, out)
            } else if (isAudioName(f.name)) {
                val uri = Uri.fromFile(f).toString()
                if (out.containsKey(uri)) continue
                val folderKey = f.parentFile?.absolutePath ?: "Telefon"
                out[uri] = AudioItem(
                    id = "file:$folderKey/${f.name}", uri = uri, title = cleanTitle(f.name),
                    artist = "", album = f.parentFile?.name ?: "Telefon",
                    durationMs = estimateDuration(f.length()),
                    folderKey = folderKey, folderName = f.parentFile?.name ?: "Telefon",
                    size = f.length(), added = f.lastModified(), track = 0,
                    genre = guessGenre(f.parentFile?.name ?: "", f.name)
                )
            }
        }
    }

    private fun walkDoc(ctx: Context, dir: DocumentFile, folderKey: String, depth: Int,
                        out: MutableMap<String, AudioItem>) {
        if (depth > 4 || out.size > 6000) return
        val children = dir.listFiles()
        for (d in children) {
            if (out.size > 6000) return
            val name = d.name ?: continue
            if (d.isDirectory) {
                if (!SKIP_DIR.matches(name)) walkDoc(ctx, d, "$folderKey/$name", depth + 1, out)
            } else if (isAudioName(name)) {
                val uri = d.uri.toString()
                if (out.containsKey(uri)) continue
                out[uri] = AudioItem(
                    id = "saf:$folderKey/$name", uri = uri, title = cleanTitle(name),
                    artist = "", album = folderKey.substringAfterLast('/'),
                    durationMs = estimateDuration(d.length()),
                    folderKey = folderKey, folderName = folderKey.substringAfterLast('/'),
                    size = d.length(), added = d.lastModified(), track = 0,
                    genre = guessGenre(folderKey, name)
                )
            }
        }
    }

    private fun buildFolders(items: List<AudioItem>): List<FolderInfo> {
        val map = LinkedHashMap<String, MutableList<AudioItem>>()
        items.forEach { map.getOrPut(it.folderKey) { ArrayList() }.add(it) }
        return map.map { (key, list) ->
            val name = key.substringAfterLast('/').ifEmpty { "Telefon" }
            FolderInfo(key, name, list.size, suggest(key, list), Store.folderSection(key))
        }.sortedWith(compareByDescending<FolderInfo> { it.count }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    /** Pravila za ime foldera; redosled odlučuje ako jedan segment sadrži više pojmova. */
    private val PATH_RULES = listOf(
        Regex("stand.?up|standup|komedij|comedy|humor|komič|komic", RegexOption.IGNORE_CASE) to Section.STANDUP,
        Regex("audio.?book|knjig|book|čitan|roman|pric[eae]", RegexOption.IGNORE_CASE) to Section.BOOKS,
        Regex("podcast|podkast|radio|emisij|epizod|serij", RegexOption.IGNORE_CASE) to Section.SERIES,
        Regex("music|muzik|mp3|album|pesm|soundtrack", RegexOption.IGNORE_CASE) to Section.MUSIC
    )

    /** Gleda celu putanju: folder najbliži fajlu pobeđuje. */
    fun suggestPath(folderKey: String): Section? {
        for (seg in pathSegments(folderKey).asReversed()) {
            for ((rx, sec) in PATH_RULES) if (rx.containsMatchIn(seg)) return sec
        }
        return null
    }

    fun suggest(folderKey: String, items: List<AudioItem>): Section? =
        suggestPath(folderKey) ?: when {
            items.size >= 3 && items.all { it.durationMs > 20 * 60_000L } -> Section.BOOKS
            items.size >= 4 && items.all { it.durationMs in 5 * 60_000L..75 * 60_000L } -> Section.SERIES
            else -> null
        }

    /** Iz imena fajla ili ručnog imena vadi izvođača za Stand up. */
    fun standupArtist(item: AudioItem): String {
        val parts = standupParts(item)
        if (parts.size >= 2) return parts[0]
        return item.artist.takeIf { it.isNotBlank() && it != "<unknown>" }
            ?: parts.firstOrNull() ?: ""
    }

    /** Iz imena fajla ili ručnog imena vadi album / show za Stand up. */
    fun standupShow(item: AudioItem): String {
        val parts = standupParts(item)
        if (parts.size >= 2) return parts[1]
        val album = item.album.takeIf { it.isNotBlank() && !it.equals(item.folderName, true) } ?: ""
        return album.ifBlank { parts.firstOrNull() ?: "" }
    }

    /** Kojoj sekciji fajl pripada: ručna ispravka > S##E## iz ručnog imena > folder. */
    fun sectionOf(item: AudioItem): Section {
        val ov = Store.override(item.id)
        if (ov != null) return ov
        if (manualSeasonOf(item) != null) return Section.SERIES
        return Store.folderSectionForPath(item.folderKey) ?: Section.NONE
    }

    /** Ručno ime sa S##E## automatski daje virtuelnu sezonu u Emisijama. */
    fun manualSeasonOf(item: AudioItem): ManualSeason? {
        val title = Store.manualTitle("item:" + item.id) ?: return null
        return manualSeasonFromTitle(title, Normalizer.seriesTitleFor(item))
    }

    /** Ručno ime ima prednost, pa normalizovano, pa original iz fajla. */
    fun displayTitle(item: AudioItem): String =
        Store.manualTitle("item:" + item.id)
            ?: Store.normalization(item.id)?.displayTitle
            ?: item.title
    /** Stabilan ključ: ručno S##E## ide pre običnog ručnog imena. */
    fun sortKeyOf(item: AudioItem): String {
        val manual = Store.manualTitle("item:" + item.id)
        if (manual != null) {
            val ms = manualSeasonFromTitle(manual, Normalizer.seriesTitleFor(item))
            if (ms != null) return ms.sortKey
            return manual.lowercase(Locale.ROOT)
        }
        return Store.normalization(item.id)?.sortKey
            ?: item.title.lowercase(Locale.ROOT)
    }

    private fun groupTitle(key: String, sec: Section, view: String, items: List<AudioItem>): String =
        when {
            sec == Section.MUSIC && view == "izvodjaci" -> items.firstOrNull()?.artist?.takeIf { it.isNotBlank() } ?: "Nepoznat izvođač"
            sec == Section.MUSIC && view == "albumi" -> items.firstOrNull()?.album?.takeIf { it.isNotBlank() } ?: "Nepoznat album"
            sec == Section.MUSIC && view == "zanr" -> items.firstOrNull()?.genre?.takeIf { it.isNotBlank() } ?: "Nepoznat žanr"
            else -> coverKeyOf(key).substringAfterLast('/').ifEmpty { "Telefon" }
        }

    /** Originalni naslov grupe, bez ručnog imena (za "Vrati original"). */
    fun originalGroupTitle(g: Group): String = when {
        g.key.startsWith("artist:") -> g.items.firstOrNull()?.artist?.takeIf { it.isNotBlank() } ?: "Nepoznat izvođač"
        g.key.startsWith("album:") -> g.items.firstOrNull()?.album?.takeIf { it.isNotBlank() } ?: "Nepoznat album"
        g.key.startsWith("genre:") -> g.items.firstOrNull()?.genre?.takeIf { it.isNotBlank() } ?: "Nepoznat žanr"
        g.key.contains("|season|") -> g.items.firstNotNullOfOrNull {
            Store.normalization(it.id)?.seasonGroupTitle
        } ?: g.title
        else -> coverKeyOf(g.key).substringAfterLast('/').ifEmpty { "Telefon" }
    }

    private val groupCacheLock = Any()
    private var groupCacheStamp: GroupCacheStamp? = null
    private val groupCache = HashMap<Pair<Section, String>, List<Group>>()

    fun groups(sec: Section, view: String = "folderi"): List<Group> {
        val stamp = GroupCacheStamp(Bus.libraryVersion, Bus.normalizationVersion, Store.epoch())
        val cacheKey = sec to view
        val hit = synchronized(groupCacheLock) {
            if (groupCacheStamp != stamp) {
                groupCache.clear()
                groupCacheStamp = stamp
            }
            groupCache[cacheKey]
        }
        if (hit != null) return hit
        val result = buildGroups(sec, view)
        synchronized(groupCacheLock) {
            if (groupCacheStamp == stamp) groupCache[cacheKey] = result
        }
        return result
    }

    private fun buildGroups(sec: Section, view: String): List<Group> {
        val selected = Bus.library.items.filter { sectionOf(it) == sec }
        val buckets = LinkedHashMap<String, MutableList<AudioItem>>()
        selected.forEach { item ->
            val baseKey = when {
                sec == Section.MUSIC && view == "izvodjaci" -> "artist:" + item.artist.ifBlank { "?" }
                sec == Section.MUSIC && view == "albumi" -> "album:" + item.album.ifBlank { "?" }
                sec == Section.MUSIC && view == "zanr" -> "genre:" + item.genre.ifBlank { "?" }
                else -> item.folderKey
            }
            // Virtuelna grupa po sezoni: potvrđena normalizacija ili ručno ime sa S##E##.
            val meta = Store.normalization(item.id)
            val manualSeason = if (sec == Section.SERIES && view == "folderi") manualSeasonOf(item) else null
            val key = when {
                sec == Section.SERIES && view == "folderi" && manualSeason != null ->
                    item.folderKey + "|season|" + String.format(Locale.US, "S%02d", manualSeason.season)
                sec == Section.SERIES && view == "folderi" && meta?.seasonGroupKey != null -> meta.seasonGroupKey
                else -> baseKey
            }
            buckets.getOrPut(key) { ArrayList() }.add(item)
        }
        val out = buckets.map { (key, list) ->
            val meta = list.firstNotNullOfOrNull { Store.normalization(it.id) }
            val firstManualSeason = if (sec == Section.SERIES && view == "folderi")
                list.firstNotNullOfOrNull { manualSeasonOf(it) } else null
            val baseTitle = when {
                sec == Section.SERIES && view == "folderi" && firstManualSeason != null ->
                    "Sezona " + String.format(Locale.US, "%02d", firstManualSeason.season)
                sec == Section.SERIES && view == "folderi" && meta?.seasonGroupTitle != null -> meta.seasonGroupTitle
                sec == Section.BOOKS && view == "folderi" ->
                    list.firstOrNull()?.let { bookGroupTitle(it) } ?: groupTitle(key, sec, view, list)
                else -> groupTitle(key, sec, view, list)
            }
            val title = Store.manualTitle("group:" + key) ?: baseTitle
            val items = list.sortedWith(compareBy({ sortKeyOf(it) }, { it.track },
                { it.title.lowercase(Locale.ROOT) }))
            val artists = items.map { it.artist }.filter { it.isNotBlank() }
            val subtitle = when {
                sec == Section.MUSIC -> "${items.size} numera"
                sec == Section.STANDUP -> {
                    val first = items.firstOrNull()
                    val a = first?.let { standupArtist(it) } ?: ""
                    val sh = first?.let { standupShow(it) } ?: ""
                    listOf(a, sh).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Nepoznat izvođač" }
                }
                sec == Section.BOOKS ->
                    items.firstNotNullOfOrNull { bookAuthor(it).takeIf { it.isNotBlank() } } ?: "Nepoznat autor"
                else -> artists.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Nepoznat autor"
            }
            val cKey = coverKeyOf(key)
            Group(key, title, subtitle, items, coverIndex(cKey),
                title.take(1).uppercase(Locale.ROOT), cKey)
        }
        return out.sortedWith(
            compareByDescending<Group> { Store.lastGroupTime(it.key) }
                .thenBy {
                    // Sezone sortiramo po |season|S01/S02/…/S10, ne po "Sezona 1/10/2".
                    if (sec == Section.SERIES && view == "folderi") it.key.lowercase(Locale.ROOT)
                    else it.title.lowercase(Locale.ROOT)
                }
        )
    }

    /** Napredak knjige kroz sve fajlove u folderu. */
    fun bookPosition(g: Group): Long {
        var sum = 0L
        for (item in g.items) {
            val p = Store.position(item.id)
            if (p > 0 && p < item.durationMs) return sum + p
            if (Store.isDone(item.id)) sum += item.durationMs
        }
        return sum
    }

    fun bookStart(g: Group): Pair<Int, Long> {
        var sum = 0L
        for ((i, item) in g.items.withIndex()) {
            val p = Store.position(item.id)
            if (p > 0 && p < item.durationMs - 2000) return i to p
            if (!Store.isDone(item.id) && p == 0L) return i to 0L
            sum += item.durationMs
        }
        return 0 to 0L
    }

    fun episodeStatus(item: AudioItem): String {
        val p = Store.position(item.id)
        return when {
            Store.isDone(item.id) || p >= item.durationMs * 0.95 && item.durationMs > 0 -> "done"
            p > 0 -> "started"
            else -> "new"
        }
    }

    /** Fajlovi koji ne liče na svoju sekciju ("Mešani fajlovi"). */
    fun mixed(): List<Pair<AudioItem, Section>> {
        val out = ArrayList<Pair<AudioItem, Section>>()
        Bus.library.items.forEach { item ->
            when {
                sectionOf(item) == Section.MUSIC &&
                    (item.durationMs > 20 * 60_000L || Regex("glava|poglavlj|chapter|epizod|dio|deo [0-9]", RegexOption.IGNORE_CASE).containsMatchIn(item.title)) ->
                    out.add(item to Section.BOOKS)
                sectionOf(item) == Section.SERIES && item.durationMs > 3 * 3600_000L ->
                    out.add(item to Section.BOOKS)
                sectionOf(item) == Section.BOOKS && item.durationMs in 1..(4 * 60_000L) ->
                    out.add(item to Section.MUSIC)
            }
        }
        return out
    }

    /** Ručno izabran omot (slika iz sistemskog izbora fajlova), keširan po grupi. */
    fun customCoverBitmap(ctx: Context, groupKey: String): Bitmap? {
        val uriString = Store.customCover(groupKey) ?: return null
        Bus.covers[groupKey]?.let { return it }
        return try {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) Bus.covers[groupKey] = bmp
            bmp
        } catch (_: Exception) { null }
    }

    /** Omot skinut u Fazi 2, keširan po osnovnom folderu / albumu / izvođaču. */
    fun cachedOnlineCover(ctx: Context, groupKey: String): Bitmap? {
        Bus.onlineCovers[groupKey]?.let { return it }
        if (Bus.onlineCoverMisses.contains(groupKey)) return null
        val e = Store.artCacheEntry(groupKey)
        if (e == null) { Bus.onlineCoverMisses.add(groupKey); return null }
        val path = e.optString("path").takeIf { it.isNotEmpty() }
        if (path == null) { Bus.onlineCoverMisses.add(groupKey); return null }
        val f = File(path)
        if (!f.exists()) { Bus.onlineCoverMisses.add(groupKey); return null }
        return try {
            val bmp = decodeSampledFile(f.absolutePath, 256)
            if (bmp != null) {
                Bus.onlineCovers[groupKey] = bmp
                Bus.onlineCoverMisses.remove(groupKey)
            } else Bus.onlineCoverMisses.add(groupKey)
            bmp
        } catch (_: Exception) {
            Bus.onlineCoverMisses.add(groupKey)
            null
        }
    }

    private fun decodeSampledFile(path: String, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        return BitmapFactory.decodeFile(path, opts)
    }

    /** Omot iz samog fajla (ID3/MP4 slika). */
    fun embeddedCover(ctx: Context, item: AudioItem): Bitmap? {
        Bus.covers[item.id]?.let { return it }
        return try {
            val r = MediaMetadataRetriever()
            if (item.uri.startsWith("content:")) r.setDataSource(ctx, Uri.parse(item.uri))
            else r.setDataSource(item.uri, HashMap<String, String>())
            val bytes = r.embeddedPicture
            r.release()
            if (bytes != null) {
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (b != null) Bus.covers[item.id] = b
                b
            } else null
        } catch (_: Exception) { null }
    }

    fun acceptSuggestions() {
        Bus.library.folders.forEach { f ->
            if (Store.folderSection(f.key) == null && f.suggested != null)
                Store.setFolderSection(f.key, f.suggested)
        }
        Bus.libraryVersion++
        Bus.log("Prihvaćeni predlozi za foldere")
    }
}
