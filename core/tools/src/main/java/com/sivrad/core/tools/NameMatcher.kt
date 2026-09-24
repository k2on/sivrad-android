package com.sivrad.core.tools

import java.text.Normalizer
import java.util.Locale

/**
 * Fuzzy matching of a spoken name against known names (contacts, app
 * labels). Speech recognition gets names wrong in ways that stay close in
 * spelling or sound ("Jon" / "John", "Signal" / "signal", "Aiden" / "Aidan"),
 * so after exact lookups fail the tools fall back to this.
 */
object NameMatcher {

    data class Match<T>(val item: T, val name: String, val score: Double)

    /**
     * The best of [candidates] for [query], or null when nothing is close
     * enough or two candidates are too close to call.
     */
    fun <T> best(
        query: String,
        candidates: List<T>,
        nameOf: (T) -> String,
        threshold: Double = 0.72,
        margin: Double = 0.08,
    ): Match<T>? {
        val ranked = candidates
            .map { Match(it, nameOf(it), score(query, nameOf(it))) }
            .sortedByDescending { it.score }
        val top = ranked.firstOrNull() ?: return null
        if (top.score < threshold) return null
        val second = ranked.drop(1).firstOrNull { it.name.normalized() != top.name.normalized() }
        if (second != null && top.score - second.score < margin) return null
        return top
    }

    /**
     * Similarity in [0, 1]: an even blend of spelling and sound similarity,
     * against the whole name and against each of its words (so "mom" finds
     * "Mom (cell)" and "sarah" finds "Sarah Connor"). Sound alone would call
     * "Jon" and "Joanna" equally close; spelling alone misses "Aiden".
     */
    fun score(query: String, name: String): Double {
        val q = query.normalized()
        val n = name.normalized()
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        val whole = similarity(q, n)
        val words = n.split(' ').filter { it.length > 1 }
        val perWord = words.maxOfOrNull { w -> similarity(q, w) } ?: 0.0
        return maxOf(whole, perWord)
    }

    private fun similarity(a: String, b: String): Double {
        val spelling = ratio(a, b)
        val sound = ratio(phoneticKey(a), phoneticKey(b)) * 0.92
        return (spelling + sound) / 2
    }

    private fun ratio(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)

    internal fun String.normalized(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /**
     * A small phonetic key, in the spirit of Metaphone: letters that sound
     * alike become one symbol, every vowel becomes `a`, runs of the same
     * symbol collapse, and a trailing vowel (usually a silent e) is dropped.
     */
    internal fun phoneticKey(s: String): String = s.split(' ').joinToString(" ") { word ->
        val w = word
            .replace("ph", "f").replace("ck", "k").replace("sch", "sk").replace("sh", "x")
            .replace("ch", "x").replace("th", "0").replace("gh", "").replace("kn", "n")
            .replace("wr", "r").replace("wh", "w").replace("qu", "kw")
            .map {
                when (it) {
                    'c', 'q', 'g', 'j' -> 'k'
                    'z' -> 's'
                    'v' -> 'f'
                    'd' -> 't'
                    'b' -> 'p'
                    'a', 'e', 'i', 'o', 'u', 'y', 'w', 'h' -> 'a'
                    else -> it
                }
            }
        val collapsed = w.fold(StringBuilder()) { sb, c -> if (sb.isEmpty() || sb.last() != c) sb.append(c) else sb }
        if (collapsed.length > 2 && collapsed.last() == 'a') collapsed.setLength(collapsed.length - 1)
        collapsed.toString()
    }

    internal fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
