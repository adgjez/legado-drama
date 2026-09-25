package com.legado.drama.engine

import com.legado.drama.engine.queue.DefaultRateGate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateGateTest {

    @Test
    fun `parseInterval falls back to default for invalid input`() {
        assertEquals(120_000L, DefaultRateGate.parseInterval(null))
        assertEquals(120_000L, DefaultRateGate.parseInterval("abc"))
        assertEquals(120_000L, DefaultRateGate.parseInterval("0"))
        assertEquals(120_000L, DefaultRateGate.parseInterval("-5"))
        assertEquals(30_000L, DefaultRateGate.parseInterval("30000"))
    }

    @Test
    fun `parseInterval accepts positive custom value`() {
        assertEquals(60_000L, DefaultRateGate.parseInterval("60000"))
    }
}