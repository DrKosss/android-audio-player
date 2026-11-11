package com.saptac.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.content.Context
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object Net {

    private fun connection(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "Saptac/1.0 (personal audio player)")
        c.instanceFollowRedirects = true
        return c
    }

    fun getText(url: String): String {
        val c = connection(url)
        try {
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    fun bitmap(url: String): Bitmap? = try {
        val c = connection(url)
        try {
            if (c.responseCode in 200..299) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val n = cm.activeNetworkInfo ?: return false
        return n.isConnected && n.type == ConnectivityManager.TYPE_WIFI
    }

    private fun parseDuration(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val s = raw.trim()
        return try {
            if (s.contains(':')) {
                val parts = s.split(':').map { it.trim().toLong() }
                when (parts.size) {
                    3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                    2 -> (parts[0] * 60 + parts[1]) * 1000L
                    else -> 0L
                }
            } else s.toLong() * 1000L
        } catch (_: Exception) { 0L }
    }

    /** RSS 2.0 + uobičajena iTunes/Atom polja. Vraća naslov feeda i epizode. */
    fun fetchFeed(url: String): Pair<String, List<FeedEpisode>> {
        val xml = getText(url)
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var title = ""
        val eps = ArrayList<FeedEpisode>()
        var inItem = false
        var epTitle = ""
        var epUrl = ""
        var epDate = ""
        var epDur = 0L
        var text = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name ?: ""
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = ""
                    when (name.lowercase(Locale.ROOT)) {
                        "item", "entry" -> {
                            inItem = true; epTitle = ""; epUrl = ""; epDate = ""; epDur = 0L
                        }
                        "enclosure" -> {
                            val u = parser.getAttributeValue(null, "url") ?: ""
                            val type = parser.getAttributeValue(null, "type") ?: ""
                            if (inItem && epUrl.isBlank() && (type.startsWith("audio") || u.contains(Regex("\\.(mp3|m4a|m4b|ogg|opus|flac|wav)", RegexOption.IGNORE_CASE))))
                                epUrl = u
                        }
                        "link" -> if (inItem && epUrl.isBlank()) {
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val rel = parser.getAttributeValue(null, "rel") ?: ""
                            if (rel == "enclosure" && href.isNotBlank()) epUrl = href
                        }
                    }
                }
                XmlPullParser.TEXT -> text = parser.text ?: ""
                XmlPullParser.END_TAG -> {
                    val n = name.lowercase(Locale.ROOT)
                    when {
                        n == "title" && !inItem && title.isBlank() -> title = text.trim()
                        n == "title" && inItem && epTitle.isBlank() -> epTitle = text.trim()
                        n == "pubdate" || n == "published" || n == "updated" ->
                            if (inItem && epDate.isBlank()) epDate = text.trim()
                        n == "duration" -> if (inItem) epDur = parseDuration(text)
                        n == "item" || n == "entry" -> {
                            if (inItem && epUrl.isNotBlank())
                                eps.add(FeedEpisode(epTitle.ifBlank { "Epizoda ${eps.size + 1}" }, epUrl, epDur, epDate))
                            inItem = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return (title.ifBlank { url.substringAfter("//").substringBefore('/') }) to eps.take(60)
    }

    /** Apple iTunes Search — besplatan katalog bez ključa. */
    fun searchCatalog(query: String): List<CatalogItem> {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = getText("https://itunes.apple.com/search?term=$q&media=podcast&entity=podcast&limit=30")
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<CatalogItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("collectionName").ifBlank { o.optString("trackName") }
            if (name.isBlank()) continue
            val count = o.optInt("trackCount", 0)
            out.add(
                CatalogItem(
                    title = name,
                    author = o.optString("artistName"),
                    genre = o.optString("primaryGenreName").ifBlank { "podkast" },
                    hint = if (count > 0) "$count epizoda" else "podkast",
                    feedUrl = o.optString("feedUrl"),
                    art = o.optString("artworkUrl100")
                )
            )
        }
        return out
    }

    /** Traži omot albuma/knjige preko istog besplatnog kataloga. */
    fun coverUrl(query: String): String? = try {
        val q = URLEncoder.encode(query, "UTF-8")
        val body = getText("https://itunes.apple.com/search?term=$q&limit=4")
        val arr = JSONObject(body).optJSONArray("results") ?: return null
        for (i in 0 until arr.length()) {
            val u = arr.optJSONObject(i)?.optString("artworkUrl100") ?: ""
            if (u.isNotBlank()) return u.replace("100x100", "600x600")
        }
        null
    } catch (_: Exception) { null }

    /** Epizoda se prvo preuzme na telefon, pa se pušta kao lokalni fajl. */
    fun download(url: String, dest: File, progress: (Int) -> Unit): Boolean {
        val c = connection(url)
        try {
            if (c.responseCode !in 200..299) return false
            val total = c.contentLength
            dest.parentFile?.mkdirs()
            val tmp = File(dest.absolutePath + ".part")
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(16 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
            if (tmp.length() < 1024) { tmp.delete(); return false }
            if (dest.exists()) dest.delete()
            return tmp.renameTo(dest)
        } finally { c.disconnect() }
    }

    /** Katalog koji radi i bez mreže (isti primeri kao u prototipu). */
    fun offlineCatalog(): List<CatalogItem> = listOf(
        CatalogItem("Hardcore History", "Dan Carlin", "istorija", "4 h po epizodi", ""),
        CatalogItem("The Rest Is History", "Goalhanger", "istorija", "2 epizode nedeljno", ""),
        CatalogItem("Istorija za početnike", "Nezavisni", "istorija", "45 min", ""),
        CatalogItem("Radio Ježevi: arhiva", "Radio", "radio", "60 min", ""),
        CatalogItem("Our Fake History", "Sebastian Major", "istorija", "50 min", ""),
        CatalogItem("Dan Carlin's Addendum", "Dan Carlin", "istorija", "30 min", ""),
        CatalogItem("The History of Rome", "Mike Duncan", "istorija", "25 min", ""),
        CatalogItem("Revolucije", "Nezavisni", "istorija", "40 min", ""),
        CatalogItem("Srpska istorija", "Arhiva", "istorija", "35 min", ""),
        CatalogItem("The Daily", "The New York Times", "vesti", "25 min", ""),
        CatalogItem("Startups For the Rest of Us", "Rob Walling", "biznis", "45 min", ""),
        CatalogItem("Noćni program", "Radio Beograd", "radio", "90 min", "")
    )
}
