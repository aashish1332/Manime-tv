package me.desidubanime.tv.util

import org.jsoup.parser.Parser

/** Title helpers shared by the DDA repo and the ADH (second-source) matcher. */
object Titles {

    /** Trailing roman numeral seasons ("Clevatess II") — V and X alone are too
     *  ambiguous (Hunter x Hunter, Level V), so they're intentionally absent. */
    private val ROMAN = mapOf(
        "ii" to 2, "iii" to 3, "iv" to 4, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9,
    )
    private val ROMAN_RX = Regex("""\s(II|III|IV|VI|VII|VIII|IX)$""", RegexOption.IGNORE_CASE)

    fun seasonOf(name: String): Int? {
        Regex("""season\s+0*(\d+)""", RegexOption.IGNORE_CASE).find(name)
            ?.let { return it.groupValues[1].toInt() }
        Regex("""(\d+)(?:st|nd|rd|th)\s+season""", RegexOption.IGNORE_CASE).find(name)
            ?.let { return it.groupValues[1].toInt() }
        ROMAN_RX.find(name.trim())
            ?.let { return ROMAN[it.groupValues[1].lowercase()] }
        // Trailing bare number ("Isekai Nonbiri Nouka 2") — but not years/parts like "Part 2".
        Regex("""\s(\d)$""").find(name.trim())
            ?.let { return it.groupValues[1].toInt() }
        return null
    }

    /**
     * Split a display title into (base name, season). Works on both our
     * "Eren the Southpaw Season 1" style and ADH's long post titles
     * ("Eren the Southpaw Season 1 Hindi, Tamil … Episodes Download"),
     * plus roman-numeral seasons ("Clevatess II" == "Clevatess Season 2").
     */
    fun baseAndSeason(title: String): Pair<String, Int> {
        Regex("""^(.*?)\s+Season\s+0*(\d+)\b""", RegexOption.IGNORE_CASE).find(title)
            ?.let { return squash(it.groupValues[1]) to it.groupValues[2].toInt() }
        Regex("""^(.*?)\s+(\d+)(?:st|nd|rd|th)\s+Season\b""", RegexOption.IGNORE_CASE).find(title)
            ?.let { return squash(it.groupValues[1]) to it.groupValues[2].toInt() }
        // "Clevatess II: Majuu no Ou…" — roman numeral followed by a subtitle.
        Regex("""^(.*?)\s(II|III|IV|VI|VII|VIII|IX)(?::.*)?$""", RegexOption.IGNORE_CASE).find(title.trim())
            ?.let { return squash(it.groupValues[1]) to (ROMAN[it.groupValues[2].lowercase()] ?: 1) }
        Regex("""^(.*)\s(\d)$""").find(title.trim())
            ?.let { return squash(it.groupValues[1]) to it.groupValues[2].toInt() }
        return squash(title) to 1
    }

    /**
     * Strip the download-site boilerplate off a post title.
     *
     * ADH and AnimeDrive both title their posts like
     *   "Sparks of Tomorrow Season 1 Hindi, English, Japanese (Multi Audio) WEB-DL Episodes Download"
     * and the scrapers used to cut at a literal `\s+Hindi\b`. That misses real
     * posts seen live:
     *   "Oshi no Ko Season 2 English, Japanese (Dual Audio) …"   <- no "Hindi"
     *   "Demon Slayer … Infinity Castle BluRay Movie Hindi, …"   <- junk before it
     * Whatever survived became part of the match key, so the same anime failed
     * to match across sites and showed up as duplicate cards.
     *
     * This cuts at the FIRST boilerplate marker of any kind — audio language
     * list, source/quality tag, or trailing "Episodes Download" phrasing — and
     * removes bracketed tags. Position 0 is never cut, so a show legitimately
     * named after one of these words survives.
     */
    fun clean(title: String): String {
        var s = stripTags(Parser.unescapeEntities(title, false))
        val cut = BOILERPLATE.find(s)?.takeIf { it.range.first > 0 }?.range?.first
        if (cut != null) s = s.substring(0, cut)
        return s.replace(Regex("""[\s\-–—:,|]+$"""), "").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Everything from here on is release metadata, not the show's name.
     * Ordered widest-first so e.g. "Multi Audio" wins over a bare "Audio".
     */
    private val BOILERPLATE = Regex(
        """\s+(?:""" +
            // audio-language enumerations ("Hindi, English, Japanese (Multi Audio)")
            """Hindi|Tamil|Telugu|Malayalam|Kannada|Bengali|Marathi|Punjabi|Urdu|""" +
            """English|Japanese|Multi[\s-]?Audio|Dual[\s-]?Audio|Dual[\s-]?Lang\w*|""" +
            // source / codec / resolution tags
            """WEB[\s-]?DL|WEB[\s-]?Rip|BluRay|BDRip|BRRip|HDRip|DVDRip|HDTV|""" +
            """HEVC|x26[45]|H\.?26[45]|10[\s-]?bit|\d{3,4}p\b|4K|2160p|""" +
            // distribution phrasing
            """Episodes?\s+Download|Download|Watch\s+Online|All\s+Episodes|""" +
            """Complete|Batch|Season\s+Pack|Uncensored|Dubbed|Subbed|""" +
            """E?Sub(?:s|bed)?|MSubs?|Movie\s+Download""" +
            """)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Cross-site match key: lowercase alphanumerics of the base name + season.
     * "Kuroko’s Basketball Season 1" == "Kurokos Basketball season 01".
     */
    fun key(title: String): String {
        val (base, season) = baseAndSeason(clean(title))
        return norm(base) + ":" + season
    }

    /**
     * All plausible keys for a title. Besides [key], covers the trailing-digit
     * ambiguity: "Thunder 3" is (Thunder, s3) by our heuristic but the digit is
     * actually part of the show's name (ADH: "Thunder 3 Season 1") — so the
     * whole-name:1 interpretation is offered too. Two titles match when their
     * key sets intersect.
     */
    fun keys(title: String): Set<String> {
        val c = clean(title)
        return setOf(key(c), norm(c) + ":1")
    }

    /**
     * What to type into a WordPress search box for this title: bracketed tags
     * and punctuation break ADH's search ("Re:ZERO -Starting…-" returns zero
     * results; "Re ZERO Starting…" finds every season), so both are dropped.
     */
    fun searchQuery(title: String): String =
        clean(title)
            .replace(Regex("[^A-Za-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Remove "[Uncensored]"-style bracketed / parenthesised release tags. */
    private fun stripTags(s: String): String =
        s.replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Significant words of a title's BASE name (season + boilerplate removed). */
    fun baseTokens(title: String): Set<String> = tokens(baseAndSeason(clean(title)).first)

    /**
     * How alike two token sets are, 0.0–1.0 (Jaccard). Used to catch cross-site
     * twins that no exact key can match because one site words the name
     * differently ("Wistoria: Wand and Sword" vs "Wistoria Wand & Sword").
     */
    fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }.toDouble()
        return inter / (a.size + b.size - inter)
    }

    /**
     * Same show? True only when the token sets are near-identical.
     *
     * The threshold is deliberately HIGH. Genuine cross-site wording variants
     * score ~0.85-1.0 — they are the same name with different punctuation or
     * one extra release word ("Wistoria Wand & Sword" vs "Wistoria: Wand and
     * Sword" = 1.0; "…Infinity Castle Movie" vs "…Infinity Castle" = 0.86).
     * Sibling shows that must stay APART sit lower ("My Hero Academia" vs "My
     * Hero Academia: Vigilantes" = 0.75, "Solo Leveling" vs "Solo Leveling
     * Arise" = 0.67). A subset rule was tried first and had to go: it cannot
     * tell a spin-off from a subtitled season, and fusing two different shows
     * makes one of them unreachable — worse than showing a duplicate.
     */
    fun sameShow(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || similarity(a, b) >= SIMILARITY_THRESHOLD
    }

    /** Tuned on real ADH/AnimeDrive title pairs — see [sameShow]. */
    private const val SIMILARITY_THRESHOLD = 0.85

    /**
     * Loose relatedness: do two titles share at least one significant word?
     * Used only as a sanity check on slug-based cross-site matches — it must
     * catch blatant mislinks ("Tamon's B-Side" -> "Skeleton Knight …") without
     * rejecting legitimate variants ("Dr. Stone Season 4" vs "Dr. Stone
     * Science Future").
     */
    fun related(a: String, b: String): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return true // nothing to compare — don't reject
        return ta.any { it in tb }
    }

    private val STOP = setOf(
        "the", "a", "an", "and", "of", "in", "on", "at", "to", "as", "is",
        "no", "na", "wa", "ni", "wo", "ga", "de", "season",
    )

    private fun tokens(s: String): Set<String> =
        Regex("[a-z0-9]+").findAll(s.lowercase().replace("’", "'").replace("'s", " "))
            .map { stem(it.value) }
            // Stem BEFORE dropping stop words: "Seasons" only becomes the stop
            // word "season" after stemming, and leaving it in made "…Four
            // Seasons Dance…" and "…Four Season Dance…" score 0.80 instead of 1.
            .filter { it.length >= 2 && it !in STOP && !it.all(Char::isDigit) }
            .toSet()

    /**
     * Crude singular-ising so a plural difference doesn't split a match:
     * one site writes "Heaven Official's Blessing", the other "Heaven Officials
     * Blessing", and after apostrophe stripping those differ by exactly one
     * token — enough to drop the similarity below the threshold.
     */
    private fun stem(w: String): String =
        if (w.length >= 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w

    private fun squash(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}
