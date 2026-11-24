package me.desidubanime.tv.tools

import me.desidubanime.tv.util.SearchMatch

/**
 * Standalone check for [SearchMatch] — the tier ranking and the one-typo matcher
 * behind search. DEBUG SOURCE SET on purpose: it never ships in a release APK,
 * and it needs no test dependency (the module has none), so it runs on the Kotlin
 * compiler the build already has:
 *
 *   gradlew.bat --init-script tools/search_check/check.init.gradle :app:searchCheck
 *
 * Exits non-zero when a check fails.
 */

private var checks = 0
private var failures = 0

private fun expect(name: String, actual: Any?, expected: Any?) {
    checks++
    val ok = actual == expected
    if (!ok) failures++
    println((if (ok) "  ok    " else "  FAIL  ") + name + if (ok) "" else "  -> got $actual, expected $expected")
}

private fun tierOf(title: String, query: String): Int =
    SearchMatch.tier(title, SearchMatch.normalize(query), SearchMatch.tokens(query))

private fun fuzzyOf(title: String, query: String): Int =
    SearchMatch.fuzzyTier(title, SearchMatch.tokens(query))

fun main() {
    val slime = "That Time I Got Reincarnated as a Slime Season 04"

    println("== strict title tiers ==")
    expect("exact    'naruto' / 'Naruto'", tierOf("Naruto", "naruto"), SearchMatch.TIER_EXACT)
    expect("prefix   'naruto' / 'Naruto Shippuden'", tierOf("Naruto Shippuden", "naruto"), SearchMatch.TIER_PREFIX)
    expect(
        "contains 'naruto' / 'Boruto: Naruto Next Generations'",
        tierOf("Boruto: Naruto Next Generations", "naruto"),
        SearchMatch.TIER_CONTAINS,
    )
    expect(
        "word order 'slime reincarnated' / full title",
        tierOf(slime, "slime reincarnated"),
        SearchMatch.TIER_WORDS,
    )
    expect("k-on     'k-on' / 'K-On!'", tierOf("K-On!", "k-on"), SearchMatch.TIER_EXACT)
    expect("k-on     'k on' / 'K-On!' (space for hyphen)", tierOf("K-On!", "k on"), SearchMatch.TIER_EXACT)
    expect("k-on     'k-on' / 'K-On! College'", tierOf("K-On! College", "k-on"), SearchMatch.TIER_PREFIX)
    expect("no match 'slime' / 'One Piece'", tierOf("One Piece", "slime"), SearchMatch.TIER_NONE)
    expect("typo is NOT a strict tier ('slmie')", tierOf(slime, "slmie"), SearchMatch.TIER_NONE)

    println("== one-typo matcher ==")
    expect("swap     'slmie'", fuzzyOf(slime, "slmie"), SearchMatch.TIER_FUZZY)
    expect("insert   'slimee'", fuzzyOf(slime, "slimee"), SearchMatch.TIER_FUZZY)
    expect("delete   'slim'", fuzzyOf(slime, "slim"), SearchMatch.TIER_FUZZY)
    expect("one bad word of two: 'slmie reincarnated'", fuzzyOf(slime, "slmie reincarnated"), SearchMatch.TIER_FUZZY)
    expect("too far  'bleach'", fuzzyOf(slime, "bleach"), SearchMatch.TIER_NONE)
    expect("short words are noise: 'solo' / 'sora'", SearchMatch.oneEditApart("solo", "sora"), false)
    expect("oneEditApart 'naruto' / 'narutoo'", SearchMatch.oneEditApart("naruto", "narutoo"), true)
    expect("oneEditApart 'demon' / 'dmeon' (swap)", SearchMatch.oneEditApart("demon", "dmeon"), true)
    expect("oneEditApart 'naruto' / 'sasuke'", SearchMatch.oneEditApart("naruto", "sasuke"), false)

    println("== result order: tier desc, then recency (as rankSearch sorts) ==")
    val qNorm = SearchMatch.normalize("naruto")
    val qTokens = SearchMatch.tokens("naruto")
    val order = listOf(
        "Boruto: Naruto Next Generations",
        "Naruto Shippuden",
        "Naruto",
        "Bleach",
    )
        .map { it to SearchMatch.tier(it, qNorm, qTokens) }
        .filter { it.second > SearchMatch.TIER_NONE }
        .sortedByDescending { it.second }
        .map { it.first }
    expect(
        "exact before prefix before contains, unrelated dropped",
        order,
        listOf("Naruto", "Naruto Shippuden", "Boruto: Naruto Next Generations"),
    )

    println()
    println(if (failures == 0) "PASS — $checks checks" else "FAIL — $failures of $checks checks")
    if (failures != 0) kotlin.system.exitProcess(1)
}
