package com.saptac.app

import java.util.Locale

/**
 * Faza 1: potpuno offline, deterministički parser imena foldera i fajlova.
 * Ne dira fajlove na disku, ne koristi mrežu, ne šalje nikakve podatke.
 * Rezultat je virtuelni NormalizedMetadata sloj u Store-u.
 */
object Normalizer {

    private val RX_SE_DASH = Regex(
        """^(.+?)\s*[-–—]\s*S(\d{1,2})\s*[-–—]\s*E(\d{1,2})(?:\s*[-–—]\s*(.+))?$""",
        RegexOption.IGNORE_CASE)
    private val RX_COMPACT = Regex(
        """^(.+?)\s*S(\d{1,2})\s*E(\d{1,2})(?:\s*[-–—]\s*(.+))?$""",
        RegexOption.IGNORE_CASE)
    private val RX_SERIES_EP = Regex(
        """^(.+?)\s*,\s*Series\s*(\d{1,2})\s*,\s*Episode\s*(\d{1,2})(?:\s*[-–—]\s*(.+))?$""",
        RegexOption.IGNORE_CASE)
    private val RX_SPECIAL = Regex(
        """^S(\d{1,2})\s*[-–—]\s*ESP\s*[-–—]\s*(.+)$""",
        RegexOption.IGNORE_CASE)
    private val RX_BARE_EP = Regex(
        """^Episode\s*(\d{1,2})(?:\s*[-–—]\s*(.+))?$""",
        RegexOption.IGNORE_CASE)
    private val RX_NUM_TITLE = Regex("""^(\d{3,4})\s*[-–—]\s*(.+)$""")

    private val SMALL = setOf("a", "an", "the", "of", "in", "on", "and", "or", "to", "for", "at", "by")

    private fun canonicalSeries(raw: String): String {
        val clean = raw.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        if (clean.isBlank()) return "Nepoznata serija"
        val words = clean.lowercase(Locale.ROOT).split(' ')
        return words.mapIndexed { i, w ->
            if (w in SMALL && i != 0 && i != words.lastIndex) w
            else w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }.joinToString(" ")
    }

    /** Ime serije iz foldera, za grupisanje internet upita. */
    fun seriesTitleFor(item: AudioItem): String = folderSeries(item)

    private fun folderSeries(item: AudioItem): String {
        val seg = pathSegments(item.folderKey).lastOrNull() ?: item.folderName
        return canonicalSeries(seg)
    }

    private fun seLabel(season: Int, episode: Int): String =
        String.format(Locale.US, "S%02dE%02d", season, episode)

    private fun seasonKey(item: AudioItem, season: Int, special: Boolean): String =
        item.folderKey + "|season|" + (if (special) "SPECIAL" else String.format(Locale.US, "S%02d", season))

    /** Naslov virtuelne grupe uvek sa dve cifre: Sezona 01, Sezona 02 … Sezona 10. */
    private fun seasonTitle(season: Int): String =
        "Sezona " + String.format(Locale.US, "%02d", season)

    private data class Parsed(
        val series: String,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val special: String?,
        val display: String,
        val sort: String?,
        val groupKey: String?,
        val groupTitle: String?,
        val confidence: Int,
        val reason: String
    )

    private fun parse(item: AudioItem): Parsed? {
        val t = item.title.trim()
        val folderSeries = folderSeries(item)

        RX_SE_DASH.matchEntire(t)?.let { m ->
            val series = canonicalSeries(m.groupValues[1])
            val se = m.groupValues[2].toInt(); val ep = m.groupValues[3].toInt()
            val et = m.groupValues.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
            val label = seLabel(se, ep)
            return Parsed(series, se, ep, et, null,
                "$series · $label" + (et?.let { " · $it" } ?: ""),
                label, seasonKey(item, se, false), seasonTitle(se), 95,
                "Prepoznat obrazac „S## - E##“ u imenu fajla")
        }
        RX_SERIES_EP.matchEntire(t)?.let { m ->
            val series = canonicalSeries(m.groupValues[1])
            val se = m.groupValues[2].toInt(); val ep = m.groupValues[3].toInt()
            val et = m.groupValues.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
            val label = seLabel(se, ep)
            return Parsed(series, se, ep, et, null,
                "$series · $label" + (et?.let { " · $it" } ?: ""),
                label, seasonKey(item, se, false), seasonTitle(se), 95,
                "Prepoznat obrazac „Series ##, Episode #“")
        }
        RX_COMPACT.matchEntire(t)?.let { m ->
            val prefix = m.groupValues[1].trim()
            val series = if (prefix.equals("TUT", true)) folderSeries else canonicalSeries(prefix)
            val se = m.groupValues[2].toInt(); val ep = m.groupValues[3].toInt()
            val et = m.groupValues.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
            val label = seLabel(se, ep)
            return Parsed(series, se, ep, et, null,
                "$series · $label" + (et?.let { " · $it" } ?: ""),
                label, seasonKey(item, se, false), seasonTitle(se), 95,
                "Prepoznat kompaktni obrazac „S##E##“")
        }
        RX_SPECIAL.matchEntire(t)?.let { m ->
            val se = m.groupValues[1].toInt()
            val name = m.groupValues[2].trim()
            return Parsed(folderSeries, se, null, null, name,
                "$folderSeries · ${String.format(Locale.US, "S%02d", se)} · $name",
                String.format(Locale.US, "S%02d-SPECIAL", se),
                seasonKey(item, se, true), "Specijali", 70,
                "Specijal „$name“ — čeka potvrdu")
        }
        RX_BARE_EP.matchEntire(t)?.let { m ->
            val ep = m.groupValues[1].toInt()
            val et = m.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            val label = String.format(Locale.US, "E%02d", ep)
            return Parsed(folderSeries, null, ep, et, null,
                "$folderSeries · $label" + (et?.let { " · $it" } ?: ""),
                label, null, null, 40,
                "Goli „Episode $ep“ bez sezone; mogući duplikat")
        }
        RX_NUM_TITLE.matchEntire(t)?.let { m ->
            val et = m.groupValues[2].trim()
            return Parsed(folderSeries, null, null, et, null,
                "$folderSeries · $et", et.lowercase(Locale.ROOT), null, null, 35,
                "Nepoznat obrazac „${m.groupValues[1]} – naslov“")
        }
        return null
    }

    /** Predlog sekcije za neraspoređene / mešane fajlove (nikad automatski). */
    private fun sectionProposal(item: AudioItem, mixedLooks: Section?, sec: Section): Section? =
        mixedLooks ?: LibraryManager.suggestPath(item.folderKey)
            ?: sec.takeIf { it != Section.NONE }

    /**
     * Pokreće se automatski posle svakog uvoza.
     * Sigurni S/E pogoci u već raspoređenim emisijama → odmah primenjeno.
     * Sve sumnjivo, 45 neraspoređenih i 115 mešanih → samo predlog, čeka klik.
     */
    fun run() {
        Store.saveNormalizationSnapshot()
        val applied = Store.allNormalizations().toMutableMap()
        val proposals = Store.allProposals().toMutableMap()
        val mixed = LibraryManager.mixed()
        val mixedIds = mixed.map { it.first.id }.toSet()
        val mixedLooks = HashMap<String, Section>()
        mixed.forEach { (item, looks) -> mixedLooks[item.id] = looks }
        val now = System.currentTimeMillis()

        Bus.library.items.forEach { item ->
            val id = item.id
            if (Store.isDismissed(id)) { proposals.remove(id); return@forEach }
            val sec = LibraryManager.sectionOf(item)
            val uncertain = sec == Section.NONE || mixedIds.contains(id)
            val parsed = parse(item)
            if (parsed == null) {
                if (uncertain) {
                    val prop = sectionProposal(item, mixedLooks[id], sec)
                    proposals[id] = NormalizedMetadata(
                        itemId = id, rawTitle = item.title, rawFolder = item.folderKey,
                        displayTitle = item.title, proposedSection = prop,
                        source = "local", confidence = 30,
                        reason = "Neraspoređen / mešani fajl — predlog: ${prop?.let { sectionName(it) } ?: "?"}",
                        normalizedAt = now)
                } else proposals.remove(id)
                return@forEach
            }
            val meta = NormalizedMetadata(
                itemId = id, rawTitle = item.title, rawFolder = item.folderKey,
                seriesTitle = parsed.series, season = parsed.season, episode = parsed.episode,
                episodeTitle = parsed.episodeTitle, special = parsed.special,
                displayTitle = parsed.display, sortKey = parsed.sort,
                seasonGroupKey = parsed.groupKey, seasonGroupTitle = parsed.groupTitle,
                proposedSection = if (sec == Section.SERIES) null else sectionProposal(item, mixedLooks[id], sec),
                source = "local", confidence = parsed.confidence,
                reason = parsed.reason + if (uncertain) " · čeka potvrdu (neraspoređen/mešani)" else "",
                normalizedAt = now)
            val auto = !uncertain && sec == Section.SERIES && parsed.confidence >= 90 &&
                parsed.season != null && parsed.episode != null && parsed.special == null
            if (auto) { applied[id] = meta; proposals.remove(id) }
            else proposals[id] = meta
        }

        Store.saveNormalizations(applied)
        Store.saveProposals(proposals)
        Bus.normalizationVersion++
        Bus.log("Sređivanje: ${applied.size} sigurnih, ${proposals.size} čeka potvrdu")
    }

    /** Korisnik je kliknuo „Primeni“ na jednom predlogu. */
    fun applyProposal(id: String) {
        val m = Store.proposal(id) ?: return
        Store.setNormalization(m)
        Store.removeProposal(id)
        Store.undismiss(id)
        Bus.normalizationVersion++
        Bus.log("Potvrđen predlog: ${m.rawTitle} → ${m.displayTitle}")
    }

    /** „Zadrži original“ — predlog se sklanja i ne vraća pri narednom uvozu. */
    fun dismissProposal(id: String) {
        Store.dismiss(id)
        Store.removeProposal(id)
        Bus.normalizationVersion++
        Bus.log("Zadržan original: $id")
    }

    /** „Vrati sve“ — tačno stanje pre poslednjeg automatskog pokretanja. */
    fun restoreAll(): Boolean {
        val ok = Store.restoreNormalizationSnapshot()
        if (ok) {
            Bus.normalizationVersion++
            Bus.log("Vraćeno sređivanje biblioteke na snapshot")
        }
        return ok
    }
}
