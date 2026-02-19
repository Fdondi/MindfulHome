package com.mindfulhome.ai

import kotlin.math.sqrt

/**
 * Produces dense embedding vectors from text using feature hashing over word
 * unigrams, bigrams, and character trigrams — the same feature set FastText
 * uses. Cosine similarity on these vectors captures lexical + sub-word overlap.
 *
 * Design note: the public API ([embed], [cosineSimilarity], [rankApps]) uses
 * plain FloatArrays, so a neural model (TFLite / MediaPipe) can be swapped in
 * later without changing callers.
 */
object EmbeddingManager {

    private const val VECTOR_DIM = 512

    private val cache = mutableMapOf<String, FloatArray>()

    /** Clears the embedding cache (call when app intent data changes). */
    fun invalidateCache() {
        cache.clear()
    }

    /**
     * Returns a dense [VECTOR_DIM]-dimensional embedding for [text].
     * Results are cached by exact text value.
     */
    fun embed(text: String): FloatArray {
        cache[text]?.let { return it }
        val vector = buildVector(text)
        cache[text] = vector
        return vector
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    /**
     * Ranks apps by cosine similarity of their text to [reason].
     *
     * @param reason  the unlock-reason text
     * @param apps    list of (packageName, appText) where appText is the label
     *                plus any accumulated past intents
     * @return  pairs of (packageName, similarity) sorted descending, filtered
     *          to similarity > 0
     */
    fun rankApps(
        reason: String,
        apps: List<Pair<String, String>>,
    ): List<Pair<String, Float>> {
        if (reason.isBlank()) return emptyList()
        val reasonVec = embed(reason)
        return apps.map { (pkg, text) ->
            pkg to cosineSimilarity(reasonVec, embed(text))
        }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
    }

    // ── internals ────────────────────────────────────────────────────

    private fun buildVector(text: String): FloatArray {
        val tokens = tokenize(text)
        val vector = FloatArray(VECTOR_DIM)
        for (token in tokens) {
            val bucket = (token.hashCode() and 0x7FFFFFFF) % VECTOR_DIM
            vector[bucket] += 1f
        }
        l2Normalize(vector)
        return vector
    }

    /**
     * Tokenizes [text] into word unigrams, word bigrams, and character
     * trigrams. The trigrams provide sub-word matching (e.g. "email" and
     * "gmail" share trigrams "mai" and "ail").
     */
    private fun tokenize(text: String): List<String> {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val tokens = mutableListOf<String>()

        // Word unigrams
        tokens.addAll(words)

        // Word bigrams
        for (i in 0 until words.size - 1) {
            tokens.add("${words[i]}_${words[i + 1]}")
        }

        // Character trigrams (prefix "#" marks word boundary)
        for (word in words) {
            val padded = "#$word#"
            for (i in 0..padded.length - 3) {
                tokens.add("_c3_${padded.substring(i, i + 3)}")
            }
        }

        return tokens
    }

    private fun l2Normalize(v: FloatArray) {
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) {
            for (i in v.indices) v[i] /= norm
        }
    }
}
