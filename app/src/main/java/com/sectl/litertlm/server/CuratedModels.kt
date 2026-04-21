package com.sectl.litertlm.server

/**
 * Curated list of models this build knows how to download. Kept small on
 * purpose — a real registry browser can come later.
 *
 * Each entry points at a Hugging Face repo + filename. We download via
 * `https://huggingface.co/<repo>/resolve/main/<filename>`. Models marked
 * [gated] = true require the user's HF token set in Settings.
 */
object CuratedModels {

    data class Entry(
        val id: String,
        val repo: String,
        val filename: String,
        val sizeLabel: String,
        val license: String,
        val gated: Boolean,
        /** Human-readable tag rendered next to the entry name, e.g.
         *  "Recommended" / "Needs 12 GB+ RAM". Null hides the badge. */
        val badge: String? = null,
    )

    // Order matters — E2B is listed first as the safe default so a fresh
    // install doesn't walk into the E4B memory trap. The adapter renders
    // the list in declaration order.
    val ALL: List<Entry> = listOf(
        Entry(
            id = "gemma-4-E2B-it.litertlm",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            filename = "gemma-4-E2B-it.litertlm",
            sizeLabel = "~1.5 GB",
            license = "Gemma Terms",
            gated = true,
            badge = "Recommended",
        ),
        Entry(
            id = "gemma-4-E4B-it.litertlm",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            filename = "gemma-4-E4B-it.litertlm",
            sizeLabel = "~3.65 GB",
            license = "Gemma Terms",
            gated = true,
            badge = "Needs 12 GB+ RAM",
        ),
    )
}
