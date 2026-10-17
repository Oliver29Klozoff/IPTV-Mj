package com.iptvapp.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelZapTest {
    @Test
    fun aSingleChannelHasNoNeighbor() {
        assertEquals(emptyList<Int>(), ChannelZap.neighborOrder(0, 1))
        assertEquals(emptyList<Int>(), ChannelZap.select(0, listOf(-1), mapOf(-1 to 2), false, false))
    }

    @Test
    fun nextComesBeforePreviousAndATwoChannelListIsNotDuplicated() {
        assertEquals(listOf(2, 0), ChannelZap.neighborOrder(1, 4))
        assertEquals(listOf(2, 0), ChannelZap.neighborOrder(1, 3))
        assertEquals(listOf(1), ChannelZap.neighborOrder(0, 2))
    }

    @Test
    fun oneConnectionAccountPreloadsNothing() {
        val spare = mapOf(-1 to ChannelZap.spareSlots(maxConnections = 1, activeConnections = 1))
        assertEquals(0, spare.getValue(-1))
        assertEquals(emptyList<Int>(), ChannelZap.select(1, listOf(-1, -1, -1), spare, false, false))
    }

    @Test
    fun unknownLimitsAreNotTreatedAsUnlimited() {
        assertEquals(0, ChannelZap.spareSlots(null, 0))
        assertEquals(0, ChannelZap.spareSlots(2, null))
        assertEquals(0, ChannelZap.spareWhilePlaying(2, null, holdsConnection = true))
    }

    @Test
    fun aZeroActiveCountWhileWatchingCountsThisDevice() {
        assertEquals(0, ChannelZap.spareWhilePlaying(1, 0, holdsConnection = true))
        assertEquals(1, ChannelZap.spareWhilePlaying(2, 0, holdsConnection = true))
        assertEquals(1, ChannelZap.spareWhilePlaying(2, 1, holdsConnection = true))
        assertEquals(2, ChannelZap.spareWhilePlaying(2, 0, holdsConnection = false))
    }

    @Test
    fun oneSparePreloadsOnlyTheNextChannel() {
        val spare = mapOf(-1 to ChannelZap.spareSlots(2, 1))
        assertEquals(listOf(2), ChannelZap.select(1, listOf(-1, -1, -1, -1), spare, false, false))
    }

    @Test
    fun twoSparesPreloadNextAndPrevious() {
        val spare = mapOf(-1 to ChannelZap.spareSlots(3, 1))
        assertEquals(listOf(2, 0), ChannelZap.select(1, listOf(-1, -1, -1, -1), spare, false, false))
    }

    @Test
    fun aNeighborOnAnotherAccountCanPreloadWhenThisAccountCannot() {
        val servers = listOf(0, 0, 1)
        val spare = mapOf(
            0 to ChannelZap.spareSlots(1, 1),
            1 to ChannelZap.spareSlots(1, 0)
        )
        assertEquals(listOf(2), ChannelZap.select(1, servers, spare, false, false))
    }

    @Test
    fun lowMemoryAndPressurePrepareNothing() {
        val spare = mapOf(-1 to 2)
        val servers = listOf(-1, -1, -1)
        assertEquals(emptyList<Int>(), ChannelZap.select(1, servers, spare, lowRamDevice = true, memoryPressure = false))
        assertEquals(emptyList<Int>(), ChannelZap.select(1, servers, spare, lowRamDevice = false, memoryPressure = true))
    }

    @Test
    fun accountBusyStatusesAreTheProviderConnectionLimits() {
        assertTrue(ChannelZap.accountBusy(460))
        assertTrue(ChannelZap.accountBusy(458))
        assertTrue(ChannelZap.accountBusy(461))
        assertFalse(ChannelZap.accountBusy(403))
        assertFalse(ChannelZap.accountBusy(null))
    }
}
