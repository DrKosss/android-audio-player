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

object LibraryManager {

    /** Jedan izvor istine: MediaStore + (opciono) direktan pristup disku + SAF folderi. */
    fun scan(ctx: Context): Library {
        val byId = LinkedHashMap<String, AudioItem>()

        // 1) MediaStore — radi sa READ_MEDIA_AUDIO bez "pristupa svim fajlovima".
        try {
            val projection = arrayOf(
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
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
            )?.use { c ->
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
                    val item = AudioItem(
                        id = "ms:$id", uri = uri,
                        title = (if (iTitle >= 0) c.getString(iTitle) else null)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: cleanTitle(name),
                        artist = (if (iArtist >= 0) c.getString(iArtist) else null)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "",
                        album = (if (iAlbum >= 0) c.getString(iAlbum) else null)
                            ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: folderName,
                        durationMs = dur,
                        folderKey = folderKey, folderName = folderName,
                        size = if (iSize >= 0) c.getLong(iSize) else 0L,
                        added = if (iDate >= 0) c.getLong(iDate) * 1000L else 0L,
                        track = if (iTrack >= 0) c.getInt(iTrack) else 0
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
                    size = doc.length(), added = doc.lastModified(), track = 0
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
                    size = f.length(), added = f.lastModified(), track = 0
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
                    size = d.length(), added = d.lastModified(), track = 0
                )
            }
        }
    }

    private fun buildFolders(items: List<AudioItem>): List<FolderInfo> {
        val map = LinkedHashMap<String, MutableList<AudioItem>>()
        items.forEach { map.getOrPut(it.folderKey) { ArrayList() }.add(it) }
        return map.map { (key, list) ->
            val name = key.substringAfterLast('/').ifEmpty { "Telefon" }
            FolderInfo(key, name, list.size, suggest(name, list), Store.folderSection(key))
        }.sortedWith(compareByDescending<FolderInfo> { it.count }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    fun suggest(name: String, items: List<AudioItem>): Section? {
        val n = name.lowercase(Locale.ROOT)
        return when {
            Regex("audio.?book|knjig|book|čitan|roman|pric[eae]").containsMatchIn(n) -> Section.BOOKS
            Regex("podcast|podkast|radio|emisij|epizod|serij").containsMatchIn(n) -> Section.SERIES
            Regex("music|muzik|mp3|album|pesm|soundtrack").containsMatchIn(n) -> Section.MUSIC
            Regex("stand.?up|standup|komedij|comedy|humor|komič|komic").containsMatchIn(n) -> Section.STANDUP
            items.size >= 3 && items.all { it.durationMs > 20 * 60_000L } -> Section.BOOKS
            items.size >= 4 && items.all { it.durationMs in 5 * 60_000L..75 * 60_000L } -> Section.SERIES
            else -> null
        }
    }

    /** Kojoj sekciji fajl pripada: pojedinačna ispravka ima prednost nad folderom. */
    fun sectionOf(item: AudioItem): Section {
        val ov = Store.override(item.id)
        if (ov != null) return ov
        return Store.folderSection(item.folderKey) ?: Section.NONE
    }

    private fun groupTitle(key: String, sec: Section, view: String, items: List<AudioItem>): String =
        when {
            sec == Section.MUSIC && view == "izvodjaci" -> items.firstOrNull()?.artist?.takeIf { it.isNotBlank() } ?: "Nepoznat izvođač"
            sec == Section.MUSIC && view == "albumi" -> items.firstOrNull()?.album?.takeIf { it.isNotBlank() } ?: "Nepoznat album"
            else -> key.substringAfterLast('/').ifEmpty { "Telefon" }
        }

    fun groups(sec: Section, view: String = "folderi"): List<Group> {
        val selected = Bus.library.items.filter { sectionOf(it) == sec }
        val buckets = LinkedHashMap<String, MutableList<AudioItem>>()
        selected.forEach { item ->
            val key = when {
                sec == Section.MUSIC && view == "izvodjaci" -> "artist:" + item.artist.ifBlank { "?" }
                sec == Section.MUSIC && view == "albumi" -> "album:" + item.album.ifBlank { "?" }
                else -> item.folderKey
            }
            buckets.getOrPut(key) { ArrayList() }.add(item)
        }
        val out = buckets.map { (key, list) ->
            val title = groupTitle(key, sec, view, list)
            val artists = list.map { it.artist }.filter { it.isNotBlank() }
            val subtitle = when {
                sec == Section.MUSIC -> "${list.size} numera"
                else -> artists.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "Nepoznat autor"
            }
            Group(key, title, subtitle, list, coverIndex(key), title.take(1).uppercase(Locale.ROOT))
        }
        return out.sortedWith(
            compareByDescending<Group> { Store.lastGroupTime(it.key) }
                .thenBy { it.title.lowercase(Locale.ROOT) }
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
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp != null) Bus.covers[groupKey] = bmp
            bmp
        } catch (_: Exception) { null }
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
