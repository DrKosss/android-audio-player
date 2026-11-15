package com.saptac.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Faza 2: internetom potpomognuto obogaćivanje VIRTUELNOG sloja.
 * Nikad ne menja fajl na disku, nikad ne primenjuje naslov automatski:
 * svaki pogodak postaje predlog u "Sređivanje biblioteke".
 * Na mrežu ide samo normalizovani upit (serija/album/izvođač), ne putanje ni URI.
 */
object OnlineEnricher {

    private const val MAX_TARGETS = 20
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    data class Hit(
        val source: String,
        val displayTitle: String,
        val episodeTitle: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val artworkUrl: String? = null,
        val confidence: Int,
        val reason: String
    )

    private data class Target(
        val key: String,
        val kind: String,
        val query: String,
        val items: MutableList<AudioItem> = ArrayList()
    )

    private fun seLabel(s: Int, e: Int): String = String.format(Locale.US, "S%02dE%02d", s, e)

    private val EP_RX = Regex(
        """S(\d{1,2})\s*E(\d{1,2})|Series\s*(\d{1,2})[, ]+Episode\s*(\d{1,2})""",
        RegexOption.IGNORE_CASE)

    private fun cleanWiki(v: String): String = v
        .replace(Regex("""\[\[(?:[^|\]]+\|)?([^\]]+)\]\]""")) { it.groupValues[1] }
        .replace(Regex("""'{2,5}"""), "")
        .replace(Regex("""<[^>]+>"""), "")
        .replace(Regex("""&[a-z]+;"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun episodesFromText(text: String, source: String, series: String,
                                 art: String?): List<Hit> {
        val out = ArrayList<Hit>()
        text.lineSequence().forEach { line ->
            val m = EP_RX.find(line) ?: return@forEach
            val s = (m.groupValues[1].takeIf { it.isNotEmpty() } ?: m.groupValues[3]).toIntOrNull()
                ?: return@forEach
            val e = (m.groupValues[2].takeIf { it.isNotEmpty() } ?: m.groupValues[4]).toIntOrNull()
                ?: return@forEach
            val tail = if (m.range.last + 1 <= line.length) line.substring(m.range.last + 1) else ""
            val title = cleanWiki(tail).trim(' ', '-', '–', '—', '|', ':', '.')
                .takeIf { it.isNotBlank() } ?: "Epizoda $e"
            out.add(Hit(source, "$series · ${seLabel(s, e)} · $title", title, s, e, art, 82,
                "$source: $line"))
        }
        return out.distinctBy { "${it.season}-${it.episode}" }
    }

    /* ------------------------------------------------------------ izvori */

    /** Apple / iTunes: besplatan katalog + RSS za originalne engleske naslove epizoda. */
    private fun itunes(series: String): List<Hit> = try {
        val cats = Net.searchCatalog(series)
        val best = cats.firstOrNull { it.feedUrl.isNotBlank() } ?: return emptyList()
        val art = best.art.takeIf { it.isNotBlank() }?.replace("100x100", "600x600")
        val (_, eps) = Net.fetchFeed(best.feedUrl)
        val out = ArrayList<Hit>()
        eps.forEach { ep ->
            val m = Regex("""S(\d{1,2})\s*E(\d{1,2})""", RegexOption.IGNORE_CASE).find(ep.title)
            if (m != null) {
                val s = m.groupValues[1].toInt(); val e = m.groupValues[2].toInt()
                val t = ep.title.substringAfter(" - ", ep.title).trim()
                out.add(Hit("Apple/iTunes", "$series · ${seLabel(s, e)} · $t", t, s, e, art, 88,
                    "iTunes RSS: ${ep.title}"))
            }
        }
        if (out.isEmpty() && art != null)
            out.add(Hit("Apple/iTunes", series, null, null, null, art, 70,
                "iTunes katalog: ${best.title}"))
        out
    } catch (_: Exception) { emptyList() }

    /** Wikipedia (i BBC spisak epizoda kroz njen sadržaj) — bez ključa. */
    private fun wikipedia(series: String): List<Hit> = try {
        val page = URLEncoder.encode(series.replace(' ', '_'), "UTF-8")
        val body = Net.getText(
            "https://en.wikipedia.org/w/api.php?action=parse&page=$page&prop=wikitext&format=json&formatversion=2")
        val wt = JSONObject(body).optJSONObject("parse")?.optString("wikitext") ?: return emptyList()
        episodesFromText(wt, "Wikipedia/BBC", series, null)
    } catch (_: Exception) { emptyList() }

    /** BBC programme guide — bez ključa, best-effort HTML. */
    private fun bbc(series: String): List<Hit> = try {
        val q = URLEncoder.encode(series, "UTF-8")
        val search = Net.getText("https://www.bbc.co.uk/search?q=$q")
        val id = Regex("""/programmes/([a-z0-9]{8})""").find(search)?.groupValues?.get(1)
            ?: return emptyList()
        val guide = Net.getText("https://www.bbc.co.uk/programmes/$id/episodes/guide")
        episodesFromText(guide, "BBC", series, null)
    } catch (_: Exception) { emptyList() }

    /** MusicBrainz — muzika, bez ključa. */
    private fun musicbrainz(query: String): List<Hit> = try {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = Net.getText("https://musicbrainz.org/ws/2/recording/?query=$q&fmt=json&limit=3")
        val arr = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
        val out = ArrayList<Hit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("title").takeIf { it.isNotBlank() } ?: continue
            val artist = o.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name") ?: ""
            out.add(Hit("MusicBrainz",
                listOf(artist, t).filter { it.isNotBlank() }.joinToString(" · "),
                t, null, null, null, 74, "MusicBrainz: $t"))
        }
        out
    } catch (_: Exception) { emptyList() }

    /** Open Library — knjige, bez ključa; donosi i URL omota. */
    private fun openLibrary(query: String): List<Hit> = try {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = Net.getText("https://openlibrary.org/search.json?q=$q&limit=3")
        val docs = JSONObject(body).optJSONArray("docs") ?: return emptyList()
        val out = ArrayList<Hit>()
        for (i in 0 until docs.length()) {
            val o = docs.optJSONObject(i) ?: continue
            val t = o.optString("title").takeIf { it.isNotBlank() } ?: continue
            val author = o.optJSONArray("author_name")?.optString(0) ?: ""
            val cover = o.optInt("cover_i", 0).takeIf { it > 0 }
                ?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg" }
            out.add(Hit("Open Library",
                listOf(t, author).filter { it.isNotBlank() }.joinToString(" · "),
                t, null, null, cover, 74, "Open Library: $t"))
        }
        out
    } catch (_: Exception) { emptyList() }

    /** Podcast Index — radi samo kada korisnik u Podešavanjima unese svoj besplatni ključ + tajnu. */
    private fun podcastIndex(series: String): List<Hit> {
        val st = Store.settings
        if (st.podcastIndexKey.isBlank() || st.podcastIndexSecret.isBlank()) return emptyList()
        return try {
            val ts = (System.currentTimeMillis() / 1000).toString()
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(st.podcastIndexSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val hash = mac.doFinal((st.podcastIndexKey + ts).toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> String.format(Locale.US, "%02x", b.toInt() and 0xff) }
            val headers = mapOf(
                "X-Auth-Key" to st.podcastIndexKey,
                "X-Auth-Date" to ts,
                "Authorization" to hash,
                "User-Agent" to "Saptac/1.1 (personal audio player)")
            val q = URLEncoder.encode(series, "UTF-8")
            val reply = Net.request(
                "https://api.podcastindex.org/api/1.0/search/byterm?q=$q&limit=3", headers)
            val body = reply.body ?: return emptyList()
            val feeds = JSONObject(body).optJSONArray("feeds") ?: return emptyList()
            val out = ArrayList<Hit>()
            for (i in 0 until feeds.length()) {
                val o = feeds.optJSONObject(i) ?: continue
                val t = o.optString("title").takeIf { it.isNotBlank() } ?: continue
                val art = o.optString("artwork").takeIf { it.isNotBlank() }
                out.add(Hit("Podcast Index", t, null, null, null, art, 72,
                    "Podcast Index: $t"))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun hitsFor(t: Target): List<Hit> = when (t.kind) {
        "series" -> itunes(t.query) + wikipedia(t.query) + bbc(t.query) + podcastIndex(t.query)
        "music" -> musicbrainz(t.query)
        "book" -> openLibrary(t.query)
        "standup" -> openLibrary(t.query) + musicbrainz(t.query)
        else -> openLibrary(t.query)
    }

    /* ------------------------------------------------------------ keš omota */

    private fun artKeys(item: AudioItem, kind: String): Set<String> {
        val out = LinkedHashSet<String>()
        out.add(coverKeyOf(item.folderKey))
        if (kind == "music") {
            out.add("album:" + item.album.ifBlank { "?" })
            out.add("artist:" + item.artist.ifBlank { "?" })
        }
        return out
    }

    private fun cacheArtwork(ctx: Context, url: String, keys: Set<String>) {
        val dir = File(ctx.filesDir, "norm-art")
        val name = (url.hashCode() and 0x7fffffff).toString(16) + ".jpg"
        val dest = File(dir, name)
        val etag = keys.firstNotNullOfOrNull { k ->
            Store.artCacheEntry(k)?.optString("etag")?.takeIf { it.isNotEmpty() }
        }
        val res = Net.downloadWithEtag(url, dest, etag)
        if (!res.ok) return
        val now = System.currentTimeMillis()
        keys.forEach { k ->
            val o = JSONObject()
            o.put("url", url); o.put("path", dest.absolutePath)
            o.put("etag", res.etag ?: JSONObject.NULL)
            o.put("fetchedAt", now); o.put("source", "online"); o.put("ttlMs", TTL_MS)
            Store.setArtCacheEntry(k, o)
            Bus.onlineCovers.remove(k) // sledeći render ponovo čita svež fajl
        }
    }

    /* ------------------------------------------------------------ predlozi */

    private fun proposalFor(item: AudioItem, t: Target, best: Hit,
                            meta: NormalizedMetadata?): NormalizedMetadata {
        val series = meta?.seriesTitle ?: Normalizer.seriesTitleFor(item)
        val se = meta?.season
        val ep = meta?.episode
        val label = if (se != null && ep != null) seLabel(se, ep) else null
        val epTitle = best.episodeTitle ?: meta?.episodeTitle
        val display = when {
            label != null && !epTitle.isNullOrBlank() -> "$series · $label · $epTitle"
            label != null -> "$series · $label"
            best.displayTitle.isNotBlank() -> best.displayTitle
            else -> LibraryManager.displayTitle(item)
        }
        val propSec = when (t.kind) {
            "series" -> Section.SERIES
            "music" -> Section.MUSIC
            "book" -> Section.BOOKS
            "standup" -> Section.STANDUP
            else -> meta?.proposedSection ?: LibraryManager.suggestPath(item.folderKey)
        }
        val now = System.currentTimeMillis()
        return NormalizedMetadata(
            itemId = item.id, rawTitle = item.title, rawFolder = item.folderKey,
            seriesTitle = if (t.kind == "series") series else meta?.seriesTitle,
            season = se, episode = ep, episodeTitle = epTitle, special = meta?.special,
            displayTitle = display,
            sortKey = meta?.sortKey ?: label,
            seasonGroupKey = meta?.seasonGroupKey,
            seasonGroupTitle = meta?.seasonGroupTitle,
            proposedSection = propSec,
            source = "online:${best.source}",
            confidence = best.confidence,
            reason = best.reason,
            normalizedAt = now,
            artworkUrl = best.artworkUrl ?: meta?.artworkUrl,
            onlineSource = best.source,
            onlineConfidence = best.confidence,
            onlineReason = best.reason,
            onlineCheckedAt = now
        )
    }

    /** Poziva se sa background niti. Vraća broj novih predloga. */
    fun enrich(ctx: Context): Int {
        val app = ctx.applicationContext
        val targets = LinkedHashMap<String, Target>()
        Bus.library.items.forEach { item ->
            val sec = LibraryManager.sectionOf(item)
            val meta = Store.normalization(item.id) ?: Store.proposal(item.id)
            val kind = when (sec) {
                Section.SERIES -> "series"
                Section.MUSIC -> "music"
                Section.BOOKS -> "book"
                Section.STANDUP -> "standup"
                else -> "none"
            }
            val query = when (kind) {
                "series" -> meta?.seriesTitle?.takeIf { it.isNotBlank() }
                    ?: Normalizer.seriesTitleFor(item)
                "music" -> listOf(item.artist, item.album).filter { it.isNotBlank() }
                    .joinToString(" ").ifBlank { item.title }
                "book" -> item.album.ifBlank { item.title }
                "standup" -> item.album.ifBlank { item.title }
                else -> item.album.ifBlank { item.title }
            }
            val key = kind + "|" + query.lowercase(Locale.ROOT)
            targets.getOrPut(key) { Target(key, kind, query) }.items.add(item)
        }
        val all = targets.values
            .sortedWith(compareByDescending<Target> { it.kind == "series" }.thenBy { it.key })
        if (all.isEmpty()) return 0
        // Svako pokretanje obradi sledećih MAX_TARGETS; posle nekoliko uvoza pokrivena je cela biblioteka.
        val start = Store.onlineCursor % all.size
        val ordered = (all.drop(start) + all.take(start)).take(MAX_TARGETS)
        Store.onlineCursor = (start + ordered.size) % all.size
        var made = 0
        ordered.forEach { t ->
            val hits = try { hitsFor(t) } catch (_: Exception) { emptyList() }
            val best = hits.maxByOrNull { it.confidence } ?: return@forEach
            if (best.artworkUrl != null) {
                val keys = LinkedHashSet<String>()
                t.items.forEach { keys.addAll(artKeys(it, t.kind)) }
                try { cacheArtwork(app, best.artworkUrl!!, keys) } catch (_: Exception) { }
            }
            t.items.forEach { item ->
                if (Store.isDismissed(item.id)) return@forEach
                val meta = Store.normalization(item.id) ?: Store.proposal(item.id)
                val existing = Store.proposal(item.id)
                // Lokalni, sigurniji predlog ne gazimo slabijim internet pogotkom.
                if (existing != null && existing.source.startsWith("local") && best.confidence < 70)
                    return@forEach
                Store.setProposal(proposalFor(item, t, best, meta))
                made++
            }
        }
        return made
    }
}
