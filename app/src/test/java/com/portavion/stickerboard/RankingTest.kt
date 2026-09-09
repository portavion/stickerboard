package com.portavion.stickerboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RankingTest {
    @Test
    fun arrangeMergesPinsThenRecentsThenIncomingRest() {
        assertEquals(
            listOf("A", "B", "C", "D", "E"),
            Ranking.of(listOf("A", "B"), listOf("C", "A", "D"))
                .arrange(listOf("C", "E", "A", "D", "B")) { it }
                .map { it.item },
        )
    }

    @Test
    fun restKeepsIncomingOrder() {
        assertEquals(
            listOf("C", "E", "A"),
            Ranking.of(emptyList(), emptyList())
                .arrange(listOf("C", "E", "A")) { it }
                .map { it.item },
        )
    }

    @Test
    fun missingShelfStemsDoNotAppearAndSizeMatchesItems() {
        val items = listOf("A", "B")
        val out = Ranking.of(listOf("A", "Z"), listOf("Y", "B")).arrange(items) { it }
        assertEquals(listOf("A", "B"), out.map { it.item })
        assertEquals(items.size, out.size)
    }

    @Test
    fun retainingDropsMissingStems() {
        val kept = Ranking.of(listOf("A", "Z"), listOf("B", "Y")).retaining(setOf("A", "B"))
        assertEquals(listOf("A"), kept.pins)
        assertEquals(listOf("B"), kept.recents)
    }

    @Test
    fun withSendMovesToFrontDedupesAndCapsAt24() {
        val once = Ranking.of(emptyList(), listOf("B", "A")).withSend("A")
        assertEquals(listOf("A", "B"), once.recents)

        val filled = (1..25).fold(Ranking.of(emptyList(), emptyList())) { acc, n ->
            acc.withSend("s$n")
        }
        assertEquals((25 downTo 2).map { "s$it" }, filled.recents)
        assertEquals(Ranking.RECENTS_MAX, filled.recents.size)
    }

    @Test
    fun withSendOfPinnedStemIsNoOp() {
        val pinned = Ranking.of(listOf("A"), listOf("B"))
        assertEquals(pinned, pinned.withSend("A"))
    }

    @Test
    fun pinKeepsRecentsStorageAndUnpinRestoresRecency() {
        val pinned = Ranking.of(emptyList(), listOf("A", "B")).withPin("A")
        assertEquals(listOf("A"), pinned.pins)
        assertEquals(listOf("A", "B"), pinned.recents)
        assertEquals(
            listOf("A", "B", "C"),
            pinned.withoutPin("A").arrange(listOf("C", "B", "A")) { it }.map { it.item },
        )
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
    fun pinFlagsMatchBandEndsAndUnpinnedHasNone() {
        val three = Ranking.of(listOf("A", "B", "C"), emptyList())
            .arrange(listOf("A", "B", "C", "D")) { it }
        val first = three[0] as Ranked.Pinned
        val mid = three[1] as Ranked.Pinned
        val last = three[2] as Ranked.Pinned
        assertFalse(first.canMoveEarlier)
        assertTrue(first.canMoveLater)
        assertTrue(mid.canMoveEarlier)
        assertTrue(mid.canMoveLater)
        assertTrue(last.canMoveEarlier)
        assertFalse(last.canMoveLater)
        assertTrue(three[3] is Ranked.Unpinned)

        val single = Ranking.of(listOf("A"), emptyList()).arrange(listOf("A")) { it }
        val only = single[0] as Ranked.Pinned
        assertFalse(only.canMoveEarlier)
        assertFalse(only.canMoveLater)
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
