package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShowRowsTest {
    private fun src(key: String, kind: ShowRows.Kind) = ShowRows.Source(key, key, kind)

    @Test fun mixedIsTheDefaultAndAnUnknownModeFallsBackToIt() {
        assertEquals(ShowRows.MIXED, ShowRows.mode(null))
        assertEquals(ShowRows.MIXED, ShowRows.mode("one"))
        assertEquals(ShowRows.BY_TITLE, ShowRows.mode("title"))
        assertEquals(ShowRows.PER_CHANNEL, ShowRows.mode("stack"))
    }

    @Test fun appsWithContinueWatchingComeFirstThenTheRestInTheirOrder() {
        val given = listOf(
            src("plex", ShowRows.Kind.OTHER), src("stremio", ShowRows.Kind.CONTINUE),
            src("spotify", ShowRows.Kind.OTHER), src("nuvio", ShowRows.Kind.CONTINUE),
        )
        assertEquals(listOf("stremio", "nuvio", "plex", "spotify"), ShowRows.ordered(given).map { it.key })
    }

    @Test fun theSavedSourceIsKeptWhileItExistsElseTheFirstOne() {
        assertEquals("b", ShowRows.current(listOf("a", "b", "c"), "b"))
        assertEquals("a", ShowRows.current(listOf("a", "b", "c"), "gone"))
        assertEquals("a", ShowRows.current(listOf("a", "b"), null))
        assertNull(ShowRows.current(emptyList(), "a"))
    }

    @Test fun byTitleGoesRoundTheList() {
        val keys = listOf("a", "b", "c")
        assertEquals("b", ShowRows.around(keys, "a", 1))
        assertEquals("a", ShowRows.around(keys, "c", 1))
        assertEquals("c", ShowRows.around(keys, "a", -1))
        assertEquals("b", ShowRows.around(keys, "c", -1))
    }

    @Test fun byTitleNeedsTwoSourcesAndAKnownOne() {
        assertNull(ShowRows.around(listOf("a"), "a", 1))
        assertNull(ShowRows.around(emptyList(), null, 1))
        assertNull(ShowRows.around(listOf("a", "b"), "gone", 1))
    }

    @Test fun perChannelStopsAtBothEnds() {
        val keys = listOf("a", "b", "c")
        assertEquals("b", ShowRows.beside(keys, "a", 1))
        assertEquals("a", ShowRows.beside(keys, "b", -1))
        assertNull(ShowRows.beside(keys, "c", 1))
        assertNull(ShowRows.beside(keys, "a", -1))
        assertNull(ShowRows.beside(keys, "gone", 1))
    }

    @Test fun onDownTheRowIsInOnItsCardsAndOnItsTitle() {
        assertEquals(true, ShowRows.slidesIn("down", onCard = true, onTitle = false))
        assertEquals(true, ShowRows.slidesIn("down", onCard = false, onTitle = true)) // By title: the title is selected first
        assertEquals(false, ShowRows.slidesIn("down", onCard = false, onTitle = false))
        assertEquals(false, ShowRows.slidesIn("always", onCard = true, onTitle = true))
        assertEquals(false, ShowRows.slidesIn("never", onCard = false, onTitle = true))
    }

    @Test fun oneSourceReplacingAnotherChangesTheStamp() {
        val cards = listOf("Show/S1/10/img")
        val before = ShowRows.stamp(listOf("nuvio" to "Nuvio", "plex" to "Plex"), "nuvio", cards)
        // Same number of sources, same cards on show: only the second source is another one.
        val after = ShowRows.stamp(listOf("nuvio" to "Nuvio", "stremio" to "Stremio"), "nuvio", cards)
        assertNotEquals(before, after)
        assertNotEquals(before, ShowRows.stamp(listOf("nuvio" to "Nuvio", "plex" to "Plex TV"), "nuvio", cards)) // renamed
        assertEquals(before, ShowRows.stamp(listOf("nuvio" to "Nuvio", "plex" to "Plex"), "nuvio", cards.toList()))
    }
}
