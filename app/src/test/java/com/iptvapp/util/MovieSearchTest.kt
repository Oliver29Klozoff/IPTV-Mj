package com.iptvapp.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MovieSearchTest {

    @Test fun normalizeDropsPrefixBracketsAndPunctuation() {
        assertEquals("Billy Madison", MovieSearch.normalizeTitle("EN - Billy Madison (1995) [1080p]"))
        assertEquals("Spider Man No Way Home", MovieSearch.normalizeTitle("Spider-Man: No Way Home"))
    }

    @Test fun compactJoinsWords() {
        assertEquals("spidermannowayhome", MovieSearch.compact("US | Spider-Man: No Way Home (2021)"))
    }

    @Test fun exactTitleBeatsLongerTitlesThatStartTheSame() {
        val titles = listOf("Alien Romulus", "EN - Alien (1979)", "Aliens", "Alien vs Predator")
        assertEquals("EN - Alien (1979)", MovieSearch.rank(titles, "alien") { it }.first())
    }

    @Test fun titleStartRanksAboveAWordInTheMiddle() {
        val titles = listOf("The Batman Returns Again", "Batman Begins")
        assertEquals(listOf("Batman Begins", "The Batman Returns Again"), MovieSearch.rank(titles, "batman") { it })
    }

    @Test fun wordsTypedJoinedStillCountAsTheTitleStart() {
        assertEquals(2, MovieSearch.score("Spider-Man: Homecoming", "spiderman"))
        assertEquals(4, MovieSearch.score("The Amazing Spider-Man", "spiderman"))
    }

    @Test fun everyWordStartingATitleWordRanksAboveLooseMatches() {
        assertEquals(3, MovieSearch.score("The Lord of the Rings", "lord rings"))
        assertEquals(4, MovieSearch.score("Overlord", "lord"))
    }

    @Test fun equallyGoodMatchesPutTheShorterTitleFirst() {
        val titles = listOf("Toy Story 3", "Toy Story")
        assertEquals(listOf("Toy Story", "Toy Story 3"), MovieSearch.rank(titles, "toy") { it })
    }
}
