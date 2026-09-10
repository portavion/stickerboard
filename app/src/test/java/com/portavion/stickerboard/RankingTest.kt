package com.portavion.stickerboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    @Test
    fun favouritesAreLivePinsInPinOrderWithMoveFlags() {
        assertEquals(
            listOf(
                Ranked.Favourite("A", canMoveEarlier = false, canMoveLater = true),
                Ranked.Favourite("B", canMoveEarlier = true, canMoveLater = false),
            ),
            Ranking.of(listOf("A", "B"), emptyList())
                .shelves(listOf("C", "E", "A", "D", "B")) { it }
                .favourites,
        )
    }

    @Test
    fun loneFavouriteKeepsDimmedArrows() {
        assertEquals(
            listOf(Ranked.Favourite("A", canMoveEarlier = false, canMoveLater = false)),
            Ranking.of(listOf("A"), emptyList()).shelves(listOf("A")) { it }.favourites,
        )
    }

    @Test
    fun recentsIsLruThenLeftoverAndIncludesPinned() {
        val recents = Ranking.of(listOf("A"), emptyList())
            .withSend("C")
            .withSend("A")
            .shelves(listOf("C", "E", "A", "D", "B")) { it }
            .recents
        assertEquals(
            listOf(
                Ranked.Recent("A", pinned = true),
                Ranked.Recent("C", pinned = false),
                Ranked.Recent("E", pinned = false),
                Ranked.Recent("D", pinned = false),
                Ranked.Recent("B", pinned = false),
            ),
            recents,
        )
    }

    @Test
    fun recentsHasEveryItemExactlyOnce() {
        val items = listOf("C", "E", "A", "D", "B")
        val shelves = Ranking.of(listOf("A"), listOf("C")).shelves(items) { it }
        assertEquals(items.sorted(), shelves.recents.map { it.item }.sorted())
        assertEquals(items.size, shelves.recents.distinctBy { it.item }.size)
        assertFalse(shelves.libraryEmpty)
        assertTrue(Ranking.of(emptyList(), emptyList()).shelves(emptyList<String>()) { it }.libraryEmpty)
        assertTrue(Shelves.empty<String>().libraryEmpty)
    }

    @Test
    fun deadPinsAndRecentsDoNotConjureSlots() {
        val shelves = Ranking.of(listOf("A", "Z"), listOf("Y", "B")).shelves(listOf("A", "B")) { it }
        assertEquals(listOf(Ranked.Favourite("A", canMoveEarlier = false, canMoveLater = false)), shelves.favourites)
        assertEquals(
            listOf(Ranked.Recent("B", pinned = false), Ranked.Recent("A", pinned = true)),
            shelves.recents,
        )
    }

    @Test
    fun withSendOfPinnedStemMovesItToFront() {
        val sent = Ranking.of(listOf("A"), listOf("B")).withSend("A")
        assertEquals(listOf("A"), sent.pins)
        assertEquals(listOf("A", "B"), sent.recents)
    }

    @Test
    fun withSendHasNoCap() {
        val filled = (1..25).fold(Ranking.of(emptyList(), emptyList())) { acc, n ->
            acc.withSend("s$n")
        }
        assertEquals((25 downTo 1).map { "s$it" }, filled.recents)
    }

    @Test
    fun pinKeepsRecentsStorageAndUnpinRestoresRecency() {
        val pinned = Ranking.of(emptyList(), listOf("A", "B")).withPin("A")
        assertEquals(listOf("A"), pinned.pins)
        assertEquals(listOf("A", "B"), pinned.recents)
        val unpinned = pinned.withoutPin("A")
        assertEquals(listOf("A", "B"), unpinned.recents)
        assertEquals(
            listOf("A", "B", "C"),
            unpinned.shelves(listOf("C", "B", "A")) { it }.recents.map { it.item },
        )
    }

    @Test
    fun retainingDropsMissingStems() {
        val kept = Ranking.of(listOf("A", "Z"), listOf("B", "Y")).retaining(setOf("A", "B"))
        assertEquals(listOf("A"), kept.pins)
        assertEquals(listOf("B"), kept.recents)
    }

    @Test
    fun pinSendAndRetainingTwiceAreEqual() {
        val empty = Ranking.of(emptyList(), emptyList())
        assertEquals(empty.withPin("A"), empty.withPin("A").withPin("A"))
        val sent = empty.withSend("A")
        assertEquals(sent, sent.withSend("A"))
        val live = Ranking.of(listOf("A", "Z"), listOf("B")).retaining(setOf("A", "B"))
        assertEquals(live, live.retaining(setOf("A", "B")))
    }

    @Test
    fun moveClampsAtEndsAndUnpinnedIsNoOp() {
        val pins = Ranking.of(listOf("A", "B"), emptyList())
        assertEquals(pins, pins.withPinMoved("A", Move.Earlier))
        assertEquals(pins, pins.withPinMoved("B", Move.Later))
        assertEquals(
            Ranking.of(listOf("B", "A"), emptyList()),
            pins.withPinMoved("A", Move.Later),
        )
        assertEquals(pins, pins.withPinMoved("C", Move.Earlier))
    }

    @Test
    fun codecRoundTripsEmptyOneStemSpacesDotsAndUnicode() {
        assertEquals(emptyList<String>(), splitStems(joinStems(emptyList())))
        assertEquals(emptyList<String>(), splitStems(null))
        assertEquals(emptyList<String>(), splitStems(""))
        assertEquals(listOf("one"), splitStems(joinStems(listOf("one"))))
        val mixed = listOf("foo bar", "a.b", "贴纸")
        assertEquals(mixed, splitStems(joinStems(mixed)))
    }

    @Test
    fun ofDropsBlanksAndSlashStemsAndKeepsPinRecentOverlap() {
        val ranking = Ranking.of(listOf("A", "", "B/C", "A"), listOf("A", "  ", "D"))
        assertEquals(listOf("A"), ranking.pins)
        assertEquals(listOf("A", "D"), ranking.recents)
    }

    @Test
    fun manifestHasNoAndroidProcess() {
        assertFalse(manifestFile().readText().contains("android:process"))
    }
}
