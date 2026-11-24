package me.desidubanime.tv.util

/**
 * Title matching for search.
 *
 * Lives here, apart from the repository, because it is pure string logic with no
 * Android types: the ranking tiers and the typo matcher can then be checked on
 * their own (tools/search_check/SearchMatchCheck.kt) instead of only by eye.
 *
 * Every source is WordPress, and WP's `search` parameter matches the post BODY
 * as well as the title — so "how well does the TITLE match" has to be computed
 * on our side, after the results arrive.
 */
object SearchMatch {

    /** No title match at all. */
    const val TIER_NONE = 0
    /** One typo away ("slmie"). */
    const val TIER_FUZZY = 1
    /** Every query word present, in any order. */
    const val TIER_WORDS = 2
    const val TIER_CONTAINS = 3
    /** Title starts with the query. */
    const val TIER_PREFIX = 4
    const val TIER_EXACT = 5

    /**
     * Under this length every pair of words is one edit apart from each other
     * ("solo"/"sora"), so a typo pass is noise rather than help.
     */
    const val FUZZY_MIN_LENGTH = 4

    /** Under this length a title match means nothing — "a" is in half the catalog. */
    const val MIN_QUERY_LENGTH = 3

    /** Lowercase, letters and digits only — "Re:ZERO -Starting Life-!" -> "rezerostartinglife". */
    fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Query words for word-order matching. Split on WHITESPACE only, so
     * punctuation inside a name stays inside it: "k-on" is one word ("kon"),
     * not "k" and "on", which together match half the catalog.
     */
    fun tokens(query: String): List<String> =
        query.lowercase()
            .split(Regex("\\s+"))
            .map { it.filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }

    /** How well one title matches the query — [TIER_NONE] when it doesn't. */
    fun tier(title: String, qNorm: String, qTokens: List<String>): Int {
        val t = normalize(title)
        if (t.isEmpty() || qNorm.isEmpty()) return TIER_NONE
        if (t == qNorm) return TIER_EXACT
        if (t.startsWith(qNorm)) return TIER_PREFIX
        if (t.contains(qNorm)) return TIER_CONTAINS
        // Word ORDER, not word sequence: "slime reincarnated" must find "That
        // Time I Got Reincarnated as a Slime". Multi-word queries only — a
        // single word is already covered by the substring check above.
        if (qTokens.size > 1 && qTokens.all { t.contains(it) }) return TIER_WORDS
        return TIER_NONE
    }

    /**
     * [TIER_FUZZY] when EVERY query word is within one edit of some word of the
     * title. The safety net for an on-screen keyboard, where "slime" routinely
     * arrives as "slim", "slmie" or "slimee".
     */
    fun fuzzyTier(title: String, qTokens: List<String>): Int {
        if (qTokens.isEmpty()) return TIER_NONE
        val titleTokens = Regex("[a-z0-9]+").findAll(title.lowercase()).map { it.value }.toList()
        if (titleTokens.isEmpty()) return TIER_NONE
        val hit = qTokens.all { qt -> titleTokens.any { tt -> oneEditApart(qt, tt) } }
        return if (hit) TIER_FUZZY else TIER_NONE
    }

    /** True when the two words differ by at most one insert, delete, substitute or swap. */
    fun oneEditApart(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < FUZZY_MIN_LENGTH || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                // Swapped neighbours ("slmie") are one slip on a keyboard, not
                // two — spend the single edit on both and move past them.
                i + 1 < a.length && j + 1 < b.length && a[i] == b[j + 1] && a[i + 1] == b[j] -> {
                    i += 2
                    j += 2
                }
                else -> {
                    i++
                    j++
                }
            }
        }
        // Whatever is left over is at most the one edit already spent.
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
