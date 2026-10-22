package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class DeveloperTapGateTest {
    @Test fun continuousTapStormTriggersExactlyOnceUntilReturning() {
        val gate=DeveloperTapGate()
        assertEquals(1,(0 until 100_000).count { gate.tap(it.toLong()) })
        assertFalse(gate.tap(1_000_000))
        gate.reset()
        repeat(9){assertFalse(gate.tap(2_000_000L+it))}
        assertTrue(gate.tap(2_000_009))
        assertFalse(gate.tap(2_000_010))
    }
    @Test fun pausedOrInterruptedSequencesMustStartOver() {
        val gate=DeveloperTapGate()
        repeat(9){assertFalse(gate.tap(it*100L))}
        assertFalse(gate.tap(2401))
        repeat(8){assertFalse(gate.tap(2402L+it))}
        assertTrue(gate.tap(2410))
        gate.reset()
        repeat(8){assertFalse(gate.tap(it.toLong()))}
        gate.reset()
        repeat(9){assertFalse(gate.tap(it.toLong()))}
        assertTrue(gate.tap(9))
    }
}
