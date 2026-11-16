package dev.plume

/**
 * The shows row, when it is split by app: which apps there are, in what order, and where Left and Right (By title)
 * or Down and Up (Per app) lead. Plain values only, so the unit tests need no Android framework.
 */
internal object ShowRows {
    const val MIXED = "mixed" // every source in one row
    const val BY_TITLE = "title" // one row at a time, switched with its title
    const val PER_CHANNEL = "stack" // one row for each app

    private val MODES = setOf(MIXED, BY_TITLE, PER_CHANNEL)

    fun mode(saved: String?) = saved?.takeIf { it in MODES } ?: MIXED

    /** What an app's row is, for the order: it has a channel named "Continue Watching", or only other channels and Watch Next. */
    enum class Kind { CONTINUE, OTHER }

    class Source(val key: String, val title: String, val kind: Kind)

    /** The apps with a Continue Watching channel first, then the others. Each group keeps the order it came in. */
    fun ordered(sources: List<Source>) = sources.sortedBy { it.kind.ordinal }

    /** The source on show: the saved one if it is still there, else the first. Null with no source. */
    fun current(keys: List<String>, saved: String?) = keys.firstOrNull { it == saved } ?: keys.firstOrNull()

    /** By title: the app [d] places to the right (+1) or left (-1) of [current], round the list. Null with fewer than two. */
    fun around(keys: List<String>, current: String?, d: Int): String? {
        val at = keys.indexOf(current)
        if (at < 0 || keys.size < 2) return null
        return keys[((at + d) % keys.size + keys.size) % keys.size]
    }

    /** Per app: the app [d] rows down (+1) or up (-1) from [current]. Null at either end. */
    fun beside(keys: List<String>, current: String?, d: Int): String? {
        val at = keys.indexOf(current)
        return if (at < 0) null else keys.getOrNull(at + d)
    }

    /** On Down, the row is in while focus is on one of its cards or on its title (By title makes the title selectable). */
    fun slidesIn(show: String, onCard: Boolean, onTitle: Boolean) = show == "down" && (onCard || onTitle)

    /**
     * What a refresh is compared on to know the row changed: the apps (key and name, in order), the one on show and its cards.
     * One app replacing another leaves their number and the cards on show as they were, but it changes what the row can switch to.
     */
    fun stamp(sources: List<Pair<String, String>>, current: String?, cards: List<String>) =
        current.orEmpty() + "|" + sources.joinToString(",") { it.first + "=" + it.second } + "|" + cards.joinToString("|")
}
